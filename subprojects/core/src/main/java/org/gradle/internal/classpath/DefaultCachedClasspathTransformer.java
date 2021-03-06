/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.classpath;

import com.google.common.collect.ImmutableList;
import org.gradle.cache.CacheRepository;
import org.gradle.cache.PersistentCache;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.concurrent.ManagedExecutor;
import org.gradle.internal.file.FileAccessTimeJournal;
import org.gradle.internal.file.FileType;
import org.gradle.internal.resource.local.FileAccessTracker;
import org.gradle.internal.snapshot.CompleteFileSystemLocationSnapshot;
import org.gradle.internal.vfs.AdditiveCache;
import org.gradle.internal.vfs.VirtualFileSystem;

import java.io.Closeable;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.function.Consumer;

public class DefaultCachedClasspathTransformer implements CachedClasspathTransformer, Closeable {

    private final PersistentCache cache;
    private final FileAccessTracker fileAccessTracker;
    private final ClasspathWalker classpathWalker;
    private final ClasspathBuilder classpathBuilder;
    private final VirtualFileSystem virtualFileSystem;
    private final List<AdditiveCache> otherCaches;
    private final ManagedExecutor executor;

    public DefaultCachedClasspathTransformer(
        CacheRepository cacheRepository,
        ClasspathTransformerCacheFactory classpathTransformerCacheFactory,
        FileAccessTimeJournal fileAccessTimeJournal,
        ClasspathWalker classpathWalker,
        ClasspathBuilder classpathBuilder,
        VirtualFileSystem virtualFileSystem,
        ExecutorFactory executorFactory,
        List<AdditiveCache> otherCaches
    ) {
        this.classpathWalker = classpathWalker;
        this.classpathBuilder = classpathBuilder;
        this.virtualFileSystem = virtualFileSystem;
        this.otherCaches = otherCaches;
        this.cache = classpathTransformerCacheFactory.createCache(cacheRepository, fileAccessTimeJournal);
        this.fileAccessTracker = classpathTransformerCacheFactory.createFileAccessTracker(fileAccessTimeJournal);
        this.executor = executorFactory.create("jar transforms");
    }

    @Override
    public ClassPath transform(ClassPath classPath, StandardTransform transform) {
        return transformFiles(classPath, fileTransformerFor(transform));
    }

    @Override
    public ClassPath transform(ClassPath classPath, StandardTransform transform, Transform additional) {
        return transformFiles(classPath, new InstrumentingClasspathFileTransformer(classpathWalker, classpathBuilder, new CompositeTransformer(additional, transformerFor(transform))));
    }

    @Override
    public Collection<URL> transform(Collection<URL> urls, StandardTransform transform) {
        if (urls.isEmpty()) {
            return ImmutableList.of();
        }
        ClasspathFileTransformer transformer = fileTransformerFor(transform);
        return cache.useCache(() -> {
            List<CacheOperation> operations = new ArrayList<>(urls.size());
            for (URL url : urls) {
                CacheOperation operation = cached(url, transformer);
                operation.schedule(executor);
                operations.add(operation);
            }
            ImmutableList.Builder<URL> cachedFiles = ImmutableList.builderWithExpectedSize(urls.size());
            for (CacheOperation operation : operations) {
                operation.collectUrl(cachedFiles::add);
            }
            return cachedFiles.build();
        });
    }

    private Transform transformerFor(StandardTransform transform) {
        if (transform == StandardTransform.BuildLogic) {
            return new InstrumentingTransformer();
        } else {
            throw new UnsupportedOperationException("Not implemented yet.");
        }
    }

    private ClassPath transformFiles(ClassPath classPath, ClasspathFileTransformer transformer) {
        if (classPath.isEmpty()) {
            return classPath;
        }
        return cache.useCache(() -> {
            List<File> originalFiles = classPath.getAsFiles();
            List<CacheOperation> operations = new ArrayList<>(originalFiles.size());
            for (File file : originalFiles) {
                CacheOperation operation = cached(file, transformer);
                operation.schedule(executor);
                operations.add(operation);
            }
            List<File> cachedFiles = new ArrayList<>(originalFiles.size());
            for (CacheOperation operation : operations) {
                operation.collect(cachedFiles::add);
            }
            return DefaultClassPath.of(cachedFiles);
        });
    }

    private ClasspathFileTransformer fileTransformerFor(StandardTransform transform) {
        switch (transform) {
            case BuildLogic:
                return new InstrumentingClasspathFileTransformer(classpathWalker, classpathBuilder, new InstrumentingTransformer());
            case None:
                return new CopyingClasspathFileTransformer(otherCaches);
            default:
                throw new IllegalArgumentException();
        }
    }

    private CacheOperation cached(URL original, ClasspathFileTransformer transformer) {
        if (original.getProtocol().equals("file")) {
            try {
                return cached(new File(original.toURI()), transformer);
            } catch (URISyntaxException e) {
                throw UncheckedException.throwAsUncheckedException(e);
            }
        }
        return new RetainUrl(original);
    }

    private CacheOperation cached(File original, ClasspathFileTransformer transformer) {
        CompleteFileSystemLocationSnapshot snapshot = virtualFileSystem.read(original.getAbsolutePath(), s -> s);
        if (snapshot.getType() == FileType.Missing) {
            return new EmptyOperation();
        }
        if (shouldUseFromCache(original)) {
            return getCachedJar(transformer, original, snapshot, cache.getBaseDir());
        }
        return new RetainFile(original);
    }

    private CacheOperation getCachedJar(ClasspathFileTransformer transformer, File original, CompleteFileSystemLocationSnapshot snapshot, File cacheDir) {
        return new TransformFile(transformer, original, snapshot, cacheDir);
    }

    private boolean shouldUseFromCache(File original) {
        // Transform everything that has not already been transformed
        return !original.toPath().startsWith(cache.getBaseDir().toPath());
    }

    @Override
    public void close() {
        CompositeStoppable.stoppable(executor, cache).stop();
    }

    interface CacheOperation {
        /**
         * Starts work on producing the result of this operation.
         */
        void schedule(Executor executor);

        /**
         * Collects the result of this operation, blocking until complete.
         */
        void collect(Consumer<File> consumer);

        /**
         * Collects the result of this operation as a URL, blocking until complete.
         */
        default void collectUrl(Consumer<URL> consumer) {
            collect(file -> {
                try {
                    consumer.accept(file.toURI().toURL());
                } catch (MalformedURLException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            });
        }
    }

    private static class RetainUrl implements CacheOperation {
        private final URL original;

        public RetainUrl(URL original) {
            this.original = original;
        }

        @Override
        public void schedule(Executor executor) {
        }

        @Override
        public void collect(Consumer<File> consumer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void collectUrl(Consumer<URL> consumer) {
            consumer.accept(original);
        }
    }

    private static class RetainFile implements CacheOperation {
        private final File original;

        public RetainFile(File original) {
            this.original = original;
        }

        @Override
        public void schedule(Executor executor) {
        }

        @Override
        public void collect(Consumer<File> consumer) {
            consumer.accept(original);
        }
    }

    private static class EmptyOperation implements CacheOperation {
        @Override
        public void schedule(Executor executor) {
        }

        @Override
        public void collect(Consumer<File> consumer) {
        }
    }

    private class TransformFile implements CacheOperation {
        private final SynchronousQueue<Object> queue;
        private final ClasspathFileTransformer transformer;
        private final File original;
        private final CompleteFileSystemLocationSnapshot snapshot;
        private final File cacheDir;

        public TransformFile(ClasspathFileTransformer transformer, File original, CompleteFileSystemLocationSnapshot snapshot, File cacheDir) {
            this.transformer = transformer;
            this.original = original;
            this.snapshot = snapshot;
            this.cacheDir = cacheDir;
            queue = new SynchronousQueue<>();
        }

        @Override
        public void schedule(Executor executor) {
            executor.execute(() -> {
                try {
                    try {
                        File result = transformer.transform(original, snapshot, cacheDir);
                        queue.put(result);
                    } catch (Throwable t) {
                        queue.put(t);
                    }
                } catch (InterruptedException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            });
        }

        @Override
        public void collect(Consumer<File> consumer) {
            Object message;
            try {
                message = queue.take();
            } catch (InterruptedException e) {
                throw UncheckedException.throwAsUncheckedException(e);
            }
            if (message instanceof Throwable) {
                throw UncheckedException.throwAsUncheckedException((Throwable) message);
            }
            File result = (File) message;
            if (!result.equals(original)) {
                fileAccessTracker.markAccessed(result);
            }
            consumer.accept(result);
        }
    }
}

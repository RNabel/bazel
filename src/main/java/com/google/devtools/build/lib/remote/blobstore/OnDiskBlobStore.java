// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote.blobstore;

import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;

import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Symlinks;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

/** A on-disk store for the remote action cache. */
public final class OnDiskBlobStore implements SimpleBlobStore {
  private final Path root;
  private Optional<Long> cacheSize = Optional.of(-1L); // In bytes.
  private Optional<Long> maxCacheSize = Optional.empty(); // In bytes.
  private double lowerBound = 0.85;
  private double upperBound = 0.95;

  public OnDiskBlobStore(Path root) {
    // TODO(robinnabel): Set the max cache size with a command line flag.
    // Purge cache directory if needed.
    ensureCacheSize();
    this.root = root;
  }

  @Override
  public boolean containsKey(String key) {
    return toPath(key).exists();
  }

  @Override
  public ListenableFuture<Boolean> get(String key, OutputStream out) {
    SettableFuture<Boolean> f = SettableFuture.create();
    Path p = toPath(key);
    if (!p.exists()) {
      f.set(false);
    } else {
      try (InputStream in = p.getInputStream()) {
        ByteStreams.copy(in, out);
        f.set(true);
      } catch (IOException e) {
        f.setException(e);
      }
    }
    return f;
  }

  @Override
  public boolean getActionResult(String key, OutputStream out)
      throws IOException, InterruptedException {
    return getFromFuture(get(key, out));
  }

  @Override
  public void put(String key, long length, InputStream in) throws IOException {
    Path target = toPath(key);
    if (target.exists()) {
      return;
    }

    // Write a temporary file first, and then rename, to avoid data corruption in case of a crash.
    Path temp = toPath(UUID.randomUUID().toString());
    try (OutputStream out = temp.getOutputStream()) {
      ByteStreams.copy(in, out);
    }
    // TODO(ulfjack): Fsync temp here before we rename it to avoid data loss in the case of machine
    // crashes (the OS may reorder the writes and the rename).
    temp.renameTo(target);

    // Update cache size.
    cacheSize = Optional.of(cacheSize.get() + length);
    ensureCacheSize();
  }

  @Override
  public void putActionResult(String key, byte[] in) throws IOException, InterruptedException {
    put(key, in.length, new ByteArrayInputStream(in));
  }

  @Override
  public void close() {}

  private long getCacheSize() {
    if (cacheSize.isPresent()) {
      return cacheSize.get();
    }

    try {
      cacheSize = Optional.of(root.getFileSize());
    } catch (IOException e) {
      // TODO error handling here.
      // TODO consider disabling cache size limiting if we cannot access folder size.
      // TODO Log warning.
      return 0;
    }

    return cacheSize.get();
  }

  private void ensureCacheSize() {
    if (!maxCacheSize.isPresent()) {
      // No cache limit.
      return;
    }
    // TODO(rnabel): delete files if needed.
    // Reduce size by 10%, once 95% is reached, s.t. disk performance does not impact runtime.
    long _cacheSize = getCacheSize();
    if (_cacheSize > Math.round(maxCacheSize.get() * upperBound)) {
      // Purging required.
      // TODO.
      try {
        Collection<Dirent> contents = root.readdir(Symlinks.NOFOLLOW);
        // FIXME should use ImmutableList.
        // Get the contents of the directory inverse sorted by atime. FIXME currently using ctime.
        Collection<Path> files = contents.stream()
                .filter(entry -> entry.getType() == Dirent.Type.FILE)
                .map(entry -> root.getChild(entry.getName()))
                // FIXME This _must_ access st_atime, but cannot as this is not exposed.
                // Looks like atime is not available on Windows...
                // Sort descending by st_ctime.
                .sorted(Comparator.comparing(e -> ((Path)e).stat().getLastChangeTime()).reversed())
//                .sorted((entry1, entry2) -> Long.compare(entry2.stat().getLastChangeTime(), entry1.stat().getLastChangeTime()))
                .collect(Collectors.toList());
        // Delete files until cache size falls below the lowerBound multiplier, starting with the oldest files.
        long requiredSize = Math.round(maxCacheSize.get() * lowerBound);
        Iterator<Path> it = files.iterator();
        while (_cacheSize > requiredSize && it.hasNext()) {
          Path currentFile = it.next();
          _cacheSize -= currentFile.getFileSize();
          currentFile.delete();
        }

      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  private Path toPath(String key) {
    return root.getChild(key);
  }
}

/*
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org>
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Git Development Community nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.spearce.jgit.lib;

import java.io.IOException;
import java.lang.ref.ReferenceQueue;

/**
 * The WindowCache manages reusable <code>Windows</code> and inflaters used by
 * the other windowed file access classes.
 */
public class WindowCache {
	private static final int KB = 1024;

	private static final int MB = 1024 * KB;

	private static final int bits(int newSize) {
		if (newSize < 4096)
			throw new IllegalArgumentException("Invalid window size");
		if (Integer.bitCount(newSize) != 1)
			throw new IllegalArgumentException("Window size must be power of 2");
		return Integer.numberOfTrailingZeros(newSize);
	}

	private static int maxByteCount;

	private static int windowSize;

	private static int windowSizeShift;

	static boolean mmap;

	static final ReferenceQueue<?> clearedWindowQueue;

	private static ByteWindow[] windows;

	private static int openWindowCount;

	private static int openByteCount;

	private static int accessClock;

	static {
		maxByteCount = 10 * MB;
		windowSizeShift = bits(8 * KB);
		windowSize = 1 << windowSizeShift;
		mmap = false;
		windows = new ByteWindow[maxByteCount / windowSize];
		clearedWindowQueue = new ReferenceQueue<Object>();
	}

	/**
	 * Modify the configuration of the window cache.
	 * <p>
	 * The new configuration is applied immediately. If the new limits are
	 * smaller than what what is currently cached, older entries will be purged
	 * as soon as possible to allow the cache to meet the new limit.
	 * 
	 * @param packedGitLimit
	 *            maximum number of bytes to hold within this instance.
	 * @param packedGitWindowSize
	 *            number of bytes per window within the cache.
	 * @param packedGitMMAP
	 *            true to enable use of mmap when creating windows.
	 * @param deltaBaseCacheLimit
	 *            number of bytes to hold in the delta base cache.
	 */
	public static void reconfigure(final int packedGitLimit,
			final int packedGitWindowSize, final boolean packedGitMMAP,
			final int deltaBaseCacheLimit) {
		reconfigureImpl(packedGitLimit, packedGitWindowSize, packedGitMMAP);
		UnpackedObjectCache.reconfigure(deltaBaseCacheLimit);
	}

	private static synchronized void reconfigureImpl(final int packedGitLimit,
			final int packedGitWindowSize, final boolean packedGitMMAP) {
		boolean prune = false;
		boolean evictAll = false;

		if (maxByteCount < packedGitLimit) {
			maxByteCount = packedGitLimit;
		} else if (maxByteCount > packedGitLimit) {
			maxByteCount = packedGitLimit;
			prune = true;
		}

		if (bits(packedGitWindowSize) != windowSizeShift) {
			windowSizeShift = bits(packedGitWindowSize);
			windowSize = 1 << windowSizeShift;
			evictAll = true;
		}

		if (mmap != packedGitMMAP) {
			mmap = packedGitMMAP;
			evictAll = true;
		}

		if (evictAll) {
			// We have to throw away every window we have. None
			// of them are suitable for the new configuration.
			//
			for (int i = 0; i < openWindowCount; i++) {
				final ByteWindow win = windows[i];
				if (--win.provider.openCount == 0)
					win.provider.cacheClose();
				windows[i] = null;
			}
			windows = new ByteWindow[maxByteCount / windowSize];
			openWindowCount = 0;
			openByteCount = 0;
		} else if (prune) {
			// Our memory limit was decreased so we should try
			// to drop windows to ensure we meet the new lower
			// limit we were just given.
			//
			final int wincnt = maxByteCount / windowSize;
			releaseMemory(wincnt, null, 0, 0);

			if (wincnt != windows.length) {
				final ByteWindow[] n = new ByteWindow[wincnt];
				System.arraycopy(windows, 0, n, 0, openWindowCount);
				windows = n;
			}
		}
	}

	/**
	 * Get a specific window.
	 * 
	 * @param curs
	 *            an active cursor object to maintain the window reference while
	 *            the caller needs it.
	 * @param wp
	 *            the provider of the window. If the window is not currently in
	 *            the cache then the provider will be asked to load it.
	 * @param position
	 *            offset (in bytes) within the file that the caller needs access
	 *            to.
	 * @throws IOException
	 *             the window was not found in the cache and the given provider
	 *             was unable to load the window on demand.
	 */
	public static synchronized final void get(final WindowCursor curs,
			final WindowedFile wp, final long position) throws IOException {
		final int id = (int) (position >> windowSizeShift);
		int idx = binarySearch(wp, id);
		if (0 <= idx) {
			final ByteWindow<?> w = windows[idx];
			if ((curs.handle = w.get()) != null) {
				w.lastAccessed = ++accessClock;
				curs.window = w;
				return;
			}
		}

		if (++wp.openCount == 1) {
			try {
				wp.cacheOpen();
			} catch (IOException ioe) {
				wp.openCount = 0;
				throw ioe;
			} catch (RuntimeException ioe) {
				wp.openCount = 0;
				throw ioe;
			} catch (Error ioe) {
				wp.openCount = 0;
				throw ioe;
			}

			// The cacheOpen may have mapped the window we are trying to
			// map ourselves. Retrying the search ensures that does not
			// happen to us.
			//
			idx = binarySearch(wp, id);
			if (0 <= idx) {
				final ByteWindow<?> w = windows[idx];
				if ((curs.handle = w.get()) != null) {
					w.lastAccessed = ++accessClock;
					curs.window = w;
					return;
				}
			}
		}

		idx = -(idx + 1);
		final int wSz = windowSize(wp, id);
		idx = releaseMemory(windows.length, wp, idx, wSz);

		if (idx < 0)
			idx = 0;
		final int toMove = openWindowCount - idx;
		if (toMove > 0)
			System.arraycopy(windows, idx, windows, idx + 1, toMove);
		wp.loadWindow(curs, id, id << windowSizeShift, wSz);
		windows[idx] = curs.window;
		openWindowCount++;
		openByteCount += curs.window.size;
	}

	private static int releaseMemory(final int maxWindowCount,
			final WindowedFile willRead, int insertionIndex, final int willAdd) {
		for (;;) {
			final ByteWindow<?> w = (ByteWindow<?>) clearedWindowQueue.poll();
			if (w == null)
				break;
			final int oldest = binarySearch(w.provider, w.id);
			if (oldest < 0 || windows[oldest] != w)
				continue; // Must have been evicted by our other controls.

			final WindowedFile p = w.provider;
			if (--p.openCount == 0 && p != willRead)
				p.cacheClose();

			openByteCount -= w.size;
			final int toMove = openWindowCount - oldest - 1;
			if (toMove > 0)
				System.arraycopy(windows, oldest + 1, windows, oldest, toMove);
			windows[--openWindowCount] = null;
			if (oldest < insertionIndex)
				insertionIndex--;
		}

		while (openWindowCount >= maxWindowCount
				|| (openWindowCount > 0 && openByteCount + willAdd > maxByteCount)) {
			int oldest = 0;
			for (int k = openWindowCount - 1; k > 0; k--) {
				if (windows[k].lastAccessed < windows[oldest].lastAccessed)
					oldest = k;
			}

			final ByteWindow w = windows[oldest];
			final WindowedFile p = w.provider;
			if (--p.openCount == 0 && p != willRead)
				p.cacheClose();

			openByteCount -= w.size;
			final int toMove = openWindowCount - oldest - 1;
			if (toMove > 0)
				System.arraycopy(windows, oldest + 1, windows, oldest, toMove);
			windows[--openWindowCount] = null;
			w.enqueue();
			if (oldest < insertionIndex)
				insertionIndex--;
		}

		return insertionIndex;
	}

	private static final int binarySearch(final WindowedFile sprov,
			final int sid) {
		if (openWindowCount == 0)
			return -1;
		final int shc = sprov.hash;
		int high = openWindowCount;
		int low = 0;
		do {
			final int mid = (low + high) / 2;
			final ByteWindow mw = windows[mid];
			if (mw.provider == sprov && mw.id == sid)
				return mid;
			final int mhc = mw.provider.hash;
			if (mhc < shc || (shc == mhc && mw.id < sid))
				low = mid + 1;
			else
				high = mid;
		} while (low < high);
		return -(low + 1);
	}

	/**
	 * Remove all windows associated with a specific provider.
	 * <p>
	 * Providers should invoke this method as part of their cleanup/close
	 * routines, ensuring that the window cache releases all windows that cannot
	 * ever be requested again.
	 * </p>
	 * 
	 * @param wp
	 *            the window provider whose windows should be removed from the
	 *            cache.
	 */
	public static synchronized final void purge(final WindowedFile wp) {
		int d = 0;
		for (int s = 0; s < openWindowCount; s++) {
			final ByteWindow win = windows[s];
			if (win.provider != wp)
				windows[d++] = win;
			else
				openByteCount -= win.size;
		}
		openWindowCount = d;

		if (wp.openCount > 0) {
			wp.openCount = 0;
			wp.cacheClose();
		}
	}

	private static int windowSize(final WindowedFile file, final int id) {
		final long len = file.length();
		final long pos = id << windowSizeShift;
		return len < pos + windowSize ? (int) (len - pos) : windowSize;
	}

	private WindowCache() {
		throw new UnsupportedOperationException();
	}
}

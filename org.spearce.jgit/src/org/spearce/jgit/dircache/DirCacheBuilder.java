/*
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

package org.spearce.jgit.dircache;

import java.util.Arrays;

/**
 * Updates a {@link DirCache} by adding individual {@link DirCacheEntry}s.
 * <p>
 * A builder always starts from a clean slate and appends in every single
 * <code>DirCacheEntry</code> which the final updated index must have to reflect
 * its new content.
 * <p>
 * For maximum performance applications should add entries in path name order.
 * Adding entries out of order is permitted, however a final sorting pass will
 * be implicitly performed during {@link #finish()} to correct any out-of-order
 * entries. Duplicate detection is also delayed until the sorting is complete.
 *
 * @see DirCacheEditor
 */
public class DirCacheBuilder extends BaseDirCacheEditor {
	private boolean sorted;

	/**
	 * Construct a new builder.
	 *
	 * @param dc
	 *            the cache this builder will eventually update.
	 * @param ecnt
	 *            estimated number of entries the builder will have upon
	 *            completion. This sizes the initial entry table.
	 */
	protected DirCacheBuilder(final DirCache dc, final int ecnt) {
		super(dc, ecnt);
	}

	/**
	 * Append one entry into the resulting entry list.
	 * <p>
	 * The entry is placed at the end of the entry list. If the entry causes the
	 * list to now be incorrectly sorted a final sorting phase will be
	 * automatically enabled within {@link #finish()}.
	 * <p>
	 * The internal entry table is automatically expanded if there is
	 * insufficient space for the new addition.
	 *
	 * @param newEntry
	 *            the new entry to add.
	 */
	public void add(final DirCacheEntry newEntry) {
		beforeAdd(newEntry);
		fastAdd(newEntry);
	}

	/**
	 * Add a range of existing entries from the destination cache.
	 * <p>
	 * The entries are placed at the end of the entry list. If any of the
	 * entries causes the list to now be incorrectly sorted a final sorting
	 * phase will be automatically enabled within {@link #finish()}.
	 * <p>
	 * This method copies from the destination cache, which has not yet been
	 * updated with this editor's new table. So all offsets into the destination
	 * cache are not affected by any updates that may be currently taking place
	 * in this editor.
	 * <p>
	 * The internal entry table is automatically expanded if there is
	 * insufficient space for the new additions.
	 *
	 * @param pos
	 *            first entry to copy from the destination cache.
	 * @param cnt
	 *            number of entries to copy.
	 */
	public void keep(final int pos, int cnt) {
		beforeAdd(cache.getEntry(pos));
		fastKeep(pos, cnt);
	}

	public void finish() {
		if (!sorted)
			resort();
		replace();
	}

	private void beforeAdd(final DirCacheEntry newEntry) {
		if (sorted && entryCnt > 0) {
			final DirCacheEntry lastEntry = entries[entryCnt - 1];
			final int cr = DirCache.cmp(lastEntry, newEntry);
			if (cr > 0) {
				// The new entry sorts before the old entry; we are
				// no longer sorted correctly. We'll need to redo
				// the sorting before we can close out the build.
				//
				sorted = false;
			} else if (cr == 0) {
				// Same file path; we can only insert this if the
				// stages won't be violated.
				//
				final int peStage = lastEntry.getStage();
				final int dceStage = newEntry.getStage();
				if (peStage == dceStage)
					throw bad(newEntry, "Duplicate stages not allowed");
				if (peStage == 0 || dceStage == 0)
					throw bad(newEntry, "Mixed stages not allowed");
				if (peStage > dceStage)
					sorted = false;
			}
		}
	}

	private void resort() {
		Arrays.sort(entries, 0, entryCnt, DirCache.ENT_CMP);

		for (int entryIdx = 1; entryIdx < entryCnt; entryIdx++) {
			final DirCacheEntry pe = entries[entryIdx - 1];
			final DirCacheEntry ce = entries[entryIdx];
			final int cr = DirCache.cmp(pe, ce);
			if (cr == 0) {
				// Same file path; we can only allow this if the stages
				// are 1-3 and no 0 exists.
				//
				final int peStage = pe.getStage();
				final int ceStage = ce.getStage();
				if (peStage == ceStage)
					throw bad(ce, "Duplicate stages not allowed");
				if (peStage == 0 || ceStage == 0)
					throw bad(ce, "Mixed stages not allowed");
			}
		}

		sorted = true;
	}

	private static IllegalStateException bad(final DirCacheEntry a,
			final String msg) {
		return new IllegalStateException(msg + ": " + a.getStage() + " "
				+ a.getPathString());
	}
}

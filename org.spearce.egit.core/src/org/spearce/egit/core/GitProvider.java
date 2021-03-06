/*******************************************************************************
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org>
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * See LICENSE for the full license text, also available.
 *******************************************************************************/
package org.spearce.egit.core;

import org.eclipse.core.resources.team.IMoveDeleteHook;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.team.core.RepositoryProvider;
import org.eclipse.team.core.history.IFileHistoryProvider;
import org.spearce.egit.core.internal.storage.GitFileHistoryProvider;
import org.spearce.egit.core.project.GitProjectData;

/**
 * The Team provider class for a Git repository.
 */
public class GitProvider extends RepositoryProvider {
	private GitProjectData data;

	private GitMoveDeleteHook hook;

	private GitFileHistoryProvider historyProvider;

	public String getID() {
		return getClass().getName();
	}

	public void configureProject() throws CoreException {
		getData().markTeamPrivateResources();
	}

	public void deconfigure() throws CoreException {
		GitProjectData.delete(getProject());
	}

	public boolean canHandleLinkedResources() {
		return true;
	}

	@Override
	public boolean canHandleLinkedResourceURI() {
		return true;
	}

	public synchronized IMoveDeleteHook getMoveDeleteHook() {
		if (hook == null) {
			hook = new GitMoveDeleteHook(getData());
		}
		return hook;
	}

	/**
	 * @return information about the mapping of an Eclipse project
	 * to a Git repository.
	 */
	public synchronized GitProjectData getData() {
		if (data == null) {
			data = GitProjectData.get(getProject());
		}
		return data;
	}

	public synchronized IFileHistoryProvider getFileHistoryProvider() {
		if (historyProvider == null) {
			historyProvider = new GitFileHistoryProvider();
		}
		return historyProvider;
	}
}

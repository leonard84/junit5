/*
 * Copyright 2015-2020 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * https://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.platform.engine.support.hierarchical;

import static java.util.Collections.singleton;
import static org.junit.platform.engine.support.hierarchical.ExclusiveResource.GLOBAL_RESOURCE_LOCK_KEY;
import static org.junit.platform.engine.support.hierarchical.Node.ExecutionMode.SAME_THREAD;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.platform.commons.util.Preconditions;
import org.junit.platform.engine.TestDescriptor;

/**
 * @since 1.3
 */
class NodeTreeWalker {

	private static final ExclusiveResource GLOBAL_WRITE_LOCK = new ExclusiveResource(GLOBAL_RESOURCE_LOCK_KEY,
		ExclusiveResource.LockMode.READ_WRITE);
	private static final ExclusiveResource GLOBAL_READ_LOCK = new ExclusiveResource(GLOBAL_RESOURCE_LOCK_KEY,
		ExclusiveResource.LockMode.READ);

	private final LockManager lockManager = new LockManager();

	NodeExecutionAdvisor walk(TestDescriptor rootDescriptor) {
		NodeExecutionAdvisor advisor = new NodeExecutionAdvisor();
		Preconditions.condition(getExclusiveResources(rootDescriptor).isEmpty(),
			"Engine descriptor must not declare exclusive resources");
		rootDescriptor.getChildren().forEach(child -> {
			walk(child, child, advisor);
		});
		return advisor;
	}

	private void walk(TestDescriptor globalLockDescriptor, TestDescriptor testDescriptor,
			NodeExecutionAdvisor advisor) {
		Set<ExclusiveResource> exclusiveResources = getExclusiveResources(testDescriptor);
		Set<ExclusiveResource> allResources = new HashSet<>(exclusiveResources);
		if (exclusiveResources.isEmpty()) {
			advisor.useResourceLock(testDescriptor, lockManager.getLockForResources(singleton(GLOBAL_READ_LOCK)));
			testDescriptor.getChildren().forEach(child -> walk(globalLockDescriptor, child, advisor));
		}
		else {
			advisor.forceDescendantExecutionMode(testDescriptor, SAME_THREAD);
			doForChildrenRecursively(testDescriptor, child -> {
				allResources.addAll(getExclusiveResources(child));
				advisor.forceDescendantExecutionMode(child, SAME_THREAD);
			});
			if (allResources.contains(GLOBAL_WRITE_LOCK)) {
				if (!globalLockDescriptor.equals(testDescriptor)) {
					advisor.forceDescendantExecutionMode(globalLockDescriptor, SAME_THREAD);
					doForChildrenRecursively(globalLockDescriptor,
						child -> advisor.forceDescendantExecutionMode(child, SAME_THREAD));
					advisor.useResourceLock(globalLockDescriptor,
						lockManager.getLockForResources(singleton(GLOBAL_WRITE_LOCK)));
				}
			}
			advisor.useResourceLock(testDescriptor, lockManager.getLockForResources(allResources));
		}
	}

	private Set<ExclusiveResource> getExclusiveResources(TestDescriptor testDescriptor) {
		return NodeUtils.asNode(testDescriptor).getExclusiveResources();
	}

	private void doForChildrenRecursively(TestDescriptor parent, Consumer<TestDescriptor> consumer) {
		parent.getChildren().forEach(child -> {
			consumer.accept(child);
			doForChildrenRecursively(child, consumer);
		});
	}

}

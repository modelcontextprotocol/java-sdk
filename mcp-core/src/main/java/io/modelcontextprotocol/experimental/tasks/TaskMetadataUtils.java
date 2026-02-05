/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.util.HashMap;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Shared utilities for adding related-task metadata to notifications and results during
 * task side-channeling.
 *
 * <p>
 * When a tool executes within a task context, outgoing notifications must carry
 * {@link McpSchema#RELATED_TASK_META_KEY} metadata so the client can correlate them back
 * to the originating task. This class centralizes that decoration logic to avoid
 * duplication across the task manager and server tool handler.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 */
public final class TaskMetadataUtils {

	private TaskMetadataUtils() {
		// Utility class — no instantiation
	}

	/**
	 * Adds related-task metadata to a notification.
	 * {@link McpSchema.TaskStatusNotification} is excluded because it already carries the
	 * taskId in its params.
	 * @param taskId the task identifier to embed in the metadata
	 * @param notification the notification to decorate
	 * @return a new notification instance with the related-task metadata merged in, or
	 * the original instance for TaskStatusNotification
	 */
	public static McpSchema.Notification addRelatedTaskMetadata(String taskId, McpSchema.Notification notification) {
		// Handle all Notification subtypes (sealed interface guarantees exhaustiveness)
		if (notification instanceof McpSchema.TaskStatusNotification tsn) {
			// Already has taskId in params — spec says SHOULD NOT include metadata
			return tsn;
		}
		else if (notification instanceof McpSchema.ProgressNotification pn) {
			Map<String, Object> newMeta = mergeRelatedTaskMetadata(taskId, pn.meta());
			return new McpSchema.ProgressNotification(pn.progressToken(), pn.progress(), pn.total(), pn.message(),
					newMeta);
		}
		else if (notification instanceof McpSchema.LoggingMessageNotification ln) {
			Map<String, Object> newMeta = mergeRelatedTaskMetadata(taskId, ln.meta());
			return new McpSchema.LoggingMessageNotification(ln.level(), ln.logger(), ln.data(), newMeta);
		}
		else if (notification instanceof McpSchema.ResourcesUpdatedNotification rn) {
			Map<String, Object> newMeta = mergeRelatedTaskMetadata(taskId, rn.meta());
			return new McpSchema.ResourcesUpdatedNotification(rn.uri(), newMeta);
		}

		// This should never happen due to sealed interface, but satisfies compiler
		throw new IllegalStateException(
				"Unexpected notification type: %s".formatted(notification.getClass().getName()));
	}

	/**
	 * Merges related-task metadata with existing metadata. Creates a new map containing
	 * the {@link McpSchema#RELATED_TASK_META_KEY} entry, then overlays any existing
	 * metadata on top.
	 * @param taskId the task identifier
	 * @param existingMeta the existing metadata map (may be null)
	 * @return a new map with the related-task metadata merged in
	 */
	public static Map<String, Object> mergeRelatedTaskMetadata(String taskId, Map<String, Object> existingMeta) {
		return mergeRelatedTaskMetadata((Object) Map.of("taskId", taskId), existingMeta);
	}

	/**
	 * Merges related-task metadata with existing metadata using an arbitrary value for
	 * the related-task entry. Creates a new map containing the
	 * {@link McpSchema#RELATED_TASK_META_KEY} entry, then overlays any existing metadata
	 * on top.
	 *
	 * <p>
	 * This overload preserves the original metadata structure, which is useful when
	 * echoing related-task metadata from an incoming request back onto an outgoing
	 * result.
	 * @param relatedTaskValue the value to store under the related-task metadata key
	 * @param existingMeta the existing metadata map (may be null)
	 * @return a new map with the related-task metadata merged in
	 */
	public static Map<String, Object> mergeRelatedTaskMetadata(Object relatedTaskValue,
			Map<String, Object> existingMeta) {
		Map<String, Object> newMeta = new HashMap<>();
		newMeta.put(McpSchema.RELATED_TASK_META_KEY, relatedTaskValue);
		if (existingMeta != null) {
			newMeta.putAll(existingMeta);
		}
		return newMeta;
	}

}

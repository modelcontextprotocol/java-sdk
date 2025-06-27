package io.modelcontextprotocol.tools.service;

import java.util.List;

import io.modelcontextprotocol.tools.util.ToolDescription;

public interface ToolGroupService {

	default List<ToolDescription> getToolDescriptions(String interfaceClassName) {
		return ToolDescription.fromService(this, interfaceClassName);
	}
}

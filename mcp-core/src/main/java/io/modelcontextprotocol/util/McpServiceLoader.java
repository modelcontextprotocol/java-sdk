package io.modelcontextprotocol.util;

import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Supplier;

public class McpServiceLoader<S extends Supplier<R>, R> {

	private Class<S> supplierType;

	private S supplier;

	private R supplierResult;

	public void setSupplier(S supplier) {
		this.supplier = supplier;
		this.supplierResult = null;
	}

	public void unsetSupplier(S supplier) {
		this.supplier = null;
		this.supplierResult = null;
	}

	public McpServiceLoader(Class<S> supplierType) {
		this.supplierType = supplierType;
	}

	protected Optional<S> serviceLoad(Class<S> type) {
		return ServiceLoader.load(type).findFirst();
	}

	@SuppressWarnings("unchecked")
	public synchronized R getDefault() {
		if (this.supplierResult == null) {
			if (this.supplier == null) {
				// Use serviceloader
				Optional<?> sl = serviceLoad(this.supplierType);
				if (sl.isEmpty()) {
					throw new ServiceConfigurationError("No JsonMapperSupplier available for creating McpJsonMapper");
				}
				this.supplier = (S) sl.get();
			}
			this.supplierResult = this.supplier.get();
		}
		return supplierResult;
	}

}

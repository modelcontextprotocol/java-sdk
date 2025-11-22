package io.modelcontextprotocol.util;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Supplier;

public class McpServiceLoader<S extends Supplier<R>, R> {

	private Type supplierType;

	private S supplier;

	private R supplierResult;

	protected abstract class TypeToken<T> {

		private Type type;

		protected TypeToken() {
			Type superClass = getClass().getGenericSuperclass();
			this.type = ((ParameterizedType) superClass).getActualTypeArguments()[0];
		}

		public Type getType() {
			return type;
		}

	}

	protected class SupplierTypeToken extends TypeToken<S> {

	};

	public void setSupplier(S supplier) {
		this.supplier = supplier;
		this.supplierResult = null;
	}

	public void unsetSupplier(S supplier) {
		this.supplier = null;
		this.supplierResult = null;
	}

	public McpServiceLoader() {
		this.supplierType = new SupplierTypeToken().getType();
	}

	protected Optional<S> serviceLoad(Class<S> type) {
		return ServiceLoader.load(type).findFirst();
	}

	@SuppressWarnings("unchecked")
	public synchronized R getDefault() {
		if (this.supplierResult == null) {
			if (this.supplier == null) {
				// Use serviceloader
				Optional<?> sl;
				try {
					sl = serviceLoad((Class<S>) Class.forName(this.supplierType.getTypeName()));
					if (sl.isEmpty()) {
						throw new ServiceConfigurationError(
								"No JsonMapperSupplier available for creating McpJsonMapper");
					}
				}
				catch (ClassNotFoundException e) {
					throw new ServiceConfigurationError(
							"ClassNotFoundException for Type=" + this.supplierType.getTypeName());
				}
				this.supplier = (S) sl.get();
			}
			this.supplierResult = this.supplier.get();
		}
		return supplierResult;
	}

}

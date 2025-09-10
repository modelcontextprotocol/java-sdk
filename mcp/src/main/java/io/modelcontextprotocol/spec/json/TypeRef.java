package io.modelcontextprotocol.spec.json;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Captures generic type information at runtime for parameterized JSON (de)serialization.
 * Usage: TypeRef<List<Foo>> ref = new TypeRef<>(){};
 */
public abstract class TypeRef<T> {

	private final Type type;

	protected TypeRef() {
		Type superClass = getClass().getGenericSuperclass();
		if (superClass instanceof Class) {
			throw new IllegalStateException("TypeRef constructed without actual type information");
		}
		this.type = ((ParameterizedType) superClass).getActualTypeArguments()[0];
	}

	public Type getType() {
		return type;
	}

}

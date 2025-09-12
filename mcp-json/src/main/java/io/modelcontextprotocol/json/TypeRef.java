package io.modelcontextprotocol.json;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Captures generic type information at runtime for parameterized JSON (de)serialization.
 * Usage: TypeRef<List<Foo>> ref = new TypeRef<>(){};
 */
public abstract class TypeRef<T> {

	private final Type type;

	/**
	 * Constructs a new TypeRef instance, capturing the generic type information of the
	 * subclass. This constructor should be called from an anonymous subclass to capture
	 * the actual type arguments. For example: <pre>
	 * TypeRef&lt;List&lt;Foo&gt;&gt; ref = new TypeRef&lt;&gt;(){};
	 * </pre>
	 * @throws IllegalStateException if TypeRef is not subclassed with actual type
	 * information
	 */
	protected TypeRef() {
		Type superClass = getClass().getGenericSuperclass();
		if (superClass instanceof Class) {
			throw new IllegalStateException("TypeRef constructed without actual type information");
		}
		this.type = ((ParameterizedType) superClass).getActualTypeArguments()[0];
	}

	/**
	 * Returns the captured type information.
	 * @return the Type representing the actual type argument captured by this TypeRef
	 * instance
	 */
	public Type getType() {
		return type;
	}

}

package io.modelcontextprotocol.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class UriTemplateTests {

	@Test
	void testValidTemplate() {
		String template = "/api/{resource}/{id}";
		UriTemplate validator = new UriTemplate(template);
		Assertions.assertEquals(template, validator.getTemplate());
	}

	@Test
	void testNullTemplate() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new UriTemplate(null));
	}

	@Test
	void testEmptyTemplate() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new UriTemplate(""));
	}

	@Test
	void testLongTemplate() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new UriTemplate("a".repeat(1_000_001)));
	}

	@Test
	void testTemplateWithEmptyExpression() {
		String template = "/api/{}";
		Assertions.assertThrows(IllegalArgumentException.class, () -> new UriTemplate(template));
	}

	@Test
	void testTemplateWithUnclosedExpression() {
		String template = "/api/{resource";
		Assertions.assertThrows(IllegalArgumentException.class, () -> new UriTemplate(template));
	}

	@Test
	void testMatchesTemplate() {
		String template = "/api/{resource}/{id}";
		UriTemplate validator = new UriTemplate(template);
		Assertions.assertTrue(validator.matchesTemplate("/api/books/123"));
		Assertions.assertFalse(validator.matchesTemplate("/api/books"));
		Assertions.assertFalse(validator.matchesTemplate("/api/books/123/extra"));
	}

	@Test
	public void testValidTemplates() {
		Assertions.assertEquals("/users/{id}", new UriTemplate("/users/{id}").getTemplate());
		Assertions.assertEquals("/search{?q}", new UriTemplate("/search{?q}").getTemplate());
		Assertions.assertEquals("/map/{+location}", new UriTemplate("/map/{+location}").getTemplate());
		Assertions.assertEquals("/path/{/segments}", new UriTemplate("/path/{/segments}").getTemplate());
		Assertions.assertEquals("/list{;item,lang}", new UriTemplate("/list{;item,lang}").getTemplate());
	}

	@Test
	public void testInvalidTemplates() {
		String[] urls = { "/bad/{id", "/mismatch/{id}/{name" };

		for (String url : urls) {
			Assertions.assertThrows(IllegalArgumentException.class, () -> new UriTemplate(url));
		}
	}

	@Test
	public void testMatchingTemplates() {
		Map<String, String> templates = Map.of("/users/{id}", "/users/123", "/search{?q}", "/search?q=test",
				"/map/{+location}", "/map/NYC", "/list{;item,lang}", "/list;item=book;lang=en");

		for (Map.Entry<String, String> entry : templates.entrySet()) {
			String template = entry.getKey();
			String url = entry.getValue();
			Assertions.assertTrue(new UriTemplate(template).matchesTemplate(url));
		}
	}

	@Test
	public void testNonMatchingTemplates() {
		Map<String, String> templates = Map.of("/users/{id}", "/posts/123", "/otherusers/{id}", "/otherusers/",
				"/users2/{id}", "/users2", "/map/{+location}", "/map/", "/path/{/segments}", "/path");

		for (Map.Entry<String, String> entry : templates.entrySet()) {
			String template = entry.getKey();
			String url = entry.getValue();
			Assertions.assertFalse(new UriTemplate(template).matchesTemplate(url));
		}
	}

}

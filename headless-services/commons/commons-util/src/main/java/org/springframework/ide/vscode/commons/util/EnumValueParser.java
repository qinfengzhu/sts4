/*******************************************************************************
 * Copyright (c) 2014-2017 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.vscode.commons.util;

import java.util.Collection;
import java.util.TreeSet;
import java.util.concurrent.Callable;

import javax.inject.Provider;

import com.google.common.collect.ImmutableSet;

/**
 * Parser for checking a 'Enum' style values.
 *
 * @author Kris De Volder
 */
public class EnumValueParser implements ValueParser {

	private String typeName;
	private Provider<PartialCollection<String>> values;

	public EnumValueParser(String typeName, String... values) {
		this(typeName, ImmutableSet.copyOf(values));
	}

	public EnumValueParser(String typeName, Collection<String> values) {
		this(typeName, provider(values));
	}

	private static <T> Provider<PartialCollection<T>> provider(Collection<T> values) {
		return () -> PartialCollection.compute(() -> values);
	}
	private static <T> Provider<PartialCollection<T>> provider(Callable<Collection<T>> values) {
		return () -> PartialCollection.compute(() -> values.call());
	}

	public EnumValueParser(String typeName, Callable<Collection<String>> values) {
		this(typeName, provider(values));
	}


	public EnumValueParser(String typeName, Provider<PartialCollection<String>> values) {
		this.typeName = typeName;
		this.values = values;
	}

	public EnumValueParser(String name, PartialCollection<String> values) {
		this(name, () -> values);
	}

	@Override
	public Object parse(String str) throws Exception {
		// IMPORTANT: check the text FIRST before fetching values
		// from the hints provider, as the hints provider may be expensive when
		// resolving values
		if (!StringUtil.hasText(str)) {
			throw errorOnBlank(createBlankTextErrorMessage());
		}

		PartialCollection<String> values = this.values.get();

		// If values is not fully known then just assume the str is acceptable.
		if (values == null || !values.isComplete() || values.getElements().contains(str)) {
			return str;
		} else {
			throw errorOnParse(createErrorMessage(str, values.getElements()));
		}
	}

	protected String createBlankTextErrorMessage() {
		return "'" + typeName + "'" + " cannot be blank.";
	}

	protected String createErrorMessage(String parseString, Collection<String> values) {
		return "'" + parseString + "' is an unknown '" + typeName + "'. Valid values are: " + new TreeSet<>(values);
	}

	protected Exception errorOnParse(String message) {
		return new ValueParseException(message);
	}

	protected Exception errorOnBlank(String message) {
		return new ValueParseException(message);
	}


}

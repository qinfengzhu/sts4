/*******************************************************************************
 * Copyright (c) 2016 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.vscode.commons.yaml.completion;

import static org.springframework.ide.vscode.commons.languageserver.completion.ScoreableProposal.DEEMP_DASH_PROPOSAL;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ide.vscode.commons.languageserver.completion.DocumentEdits;
import org.springframework.ide.vscode.commons.languageserver.completion.ICompletionProposal;
import org.springframework.ide.vscode.commons.languageserver.util.DocumentRegion;
import org.springframework.ide.vscode.commons.util.CollectionUtil;
import org.springframework.ide.vscode.commons.util.ExceptionUtil;
import org.springframework.ide.vscode.commons.util.FuzzyMatcher;
import org.springframework.ide.vscode.commons.util.Log;
import org.springframework.ide.vscode.commons.util.Renderable;
import org.springframework.ide.vscode.commons.util.ValueParseException;
import org.springframework.ide.vscode.commons.yaml.completion.DefaultCompletionFactory.ValueProposal;
import org.springframework.ide.vscode.commons.yaml.hover.YPropertyInfoTemplates;
import org.springframework.ide.vscode.commons.yaml.path.YamlPath;
import org.springframework.ide.vscode.commons.yaml.path.YamlPathSegment;
import org.springframework.ide.vscode.commons.yaml.path.YamlPathSegment.YamlPathSegmentType;
import org.springframework.ide.vscode.commons.yaml.schema.DynamicSchemaContext;
import org.springframework.ide.vscode.commons.yaml.schema.ISubCompletionEngine;
import org.springframework.ide.vscode.commons.yaml.schema.SNodeDynamicSchemaContext;
import org.springframework.ide.vscode.commons.yaml.schema.YType;
import org.springframework.ide.vscode.commons.yaml.schema.YTypeUtil;
import org.springframework.ide.vscode.commons.yaml.schema.YTypedProperty;
import org.springframework.ide.vscode.commons.yaml.schema.YValueHint;
import org.springframework.ide.vscode.commons.yaml.structure.YamlDocument;
import org.springframework.ide.vscode.commons.yaml.structure.YamlStructureParser.SNode;
import org.springframework.ide.vscode.commons.yaml.util.YamlIndentUtil;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

public class YTypeAssistContext extends AbstractYamlAssistContext {

	final static Logger logger = LoggerFactory.getLogger(YTypeAssistContext.class);

	final private YTypeUtil typeUtil;
	final private YType type;
	final private YamlAssistContext parent;

	/**
	 * Create a 'relaxed' {@link YTypeAssistContext} that pretends the type expected in this context
	 * is actually something else than the schema sugests.
	 */
	public YTypeAssistContext(YTypeAssistContext relaxationTarget, YType relaxedType) {
		super(relaxationTarget.getDocument(), relaxationTarget.documentSelector, relaxationTarget.contextPath);
		this.parent = relaxationTarget.parent;
		this.typeUtil = relaxationTarget.typeUtil;
		this.type = relaxedType;
	}

	public YTypeAssistContext(YTypeAssistContext parent, YamlPath contextPath, YType YType, YTypeUtil typeUtil) {
		super(parent.getDocument(), parent.documentSelector, contextPath);
		this.parent = parent;
		this.typeUtil = typeUtil;
		this.type = typeUtil.inferMoreSpecificType(YType, getSchemaContext());
	}

	public YTypeAssistContext(TopLevelAssistContext parent, int documentSelector, YType type, YTypeUtil typeUtil) {
		super(parent.getDocument(), documentSelector, YamlPath.EMPTY);
		this.parent = parent;
		this.typeUtil = typeUtil;
		this.type = type;
	}

	@Override
	public Collection<ICompletionProposal> getCompletions(YamlDocument doc, SNode node, int offset) throws Exception {
		ISubCompletionEngine customContentAssistant = typeUtil.getCustomContentAssistant(type);
		if (customContentAssistant!=null) {
			DocumentRegion region = getCustomAssistRegion(doc, node, offset);
			if (region!=null) {
				return customContentAssistant.getCompletions(completionFactory(), region, region.toRelative(offset));
			}
		}
		String query = getPrefix(doc, node, offset);
		List<ICompletionProposal> completions = getValueCompletions(doc, node, offset, query);
		if (completions.isEmpty()) {
			completions = getKeyCompletions(doc, offset, query);
		}
		if (typeUtil.isSequencable(type)) {
			completions = new ArrayList<>(completions);
			completions.addAll(getDashedCompletions(doc, node, offset));
		}
		return completions;
	}

	public List<ICompletionProposal> getKeyCompletions(YamlDocument doc, int offset, String query) throws Exception {
		int queryOffset = offset - query.length();
		SNode contextNode = getContextNode();
		DynamicSchemaContext dynamicCtxt = getSchemaContext();
		List<YTypedProperty> allProperties = typeUtil.getProperties(type);
		if (CollectionUtil.hasElements(allProperties)) {
			List<List<YTypedProperty>> tieredProperties = sortIntoTiers(allProperties);
			Set<String> definedProps = dynamicCtxt.getDefinedProperties();
			for (List<YTypedProperty> thisTier : tieredProperties) {
				List<YTypedProperty> undefinedProps = thisTier.stream()
						.filter(p -> !definedProps.contains(p.getName()))
						.collect(Collectors.toList());
				if (!undefinedProps.isEmpty()) {
					List<ICompletionProposal> proposals = new ArrayList<>();
					for (YTypedProperty p : undefinedProps) {
						String name = p.getName();
						double score = FuzzyMatcher.matchScore(query, name);
						if (score!=0) {
							YamlPath relativePath = YamlPath.fromSimpleProperty(name);
							YamlPathEdits edits = new YamlPathEdits(doc);
							YType YType = p.getType();
							edits.delete(queryOffset, query);
							if (queryOffset>0 && !Character.isWhitespace(doc.getChar(queryOffset-1))) {
								//See https://www.pivotaltracker.com/story/show/137722057
								edits.insert(queryOffset, " ");
							}
							edits.createPathInPlace(contextNode, relativePath, queryOffset, appendTextFor(YType));
							proposals.add(completionFactory().beanProperty(doc.getDocument(),
									contextPath.toPropString(), getType(),
									query, p, score, edits, typeUtil)
							);
						}
					}
					return proposals;
				}
			}
		}
		return Collections.emptyList();
	}

	/**
	 * Divides a given list of properties into tiers of decreasing significance. Property tiering
	 * is a mechanism to reduce 'noise' in content assist proposals. Only properties of the
	 * first tier that some still undefined properties will be used to generate proposals.
	 * <p>
	 * This allows, for example, to only suggest a 'name' property when starting to define
	 * a new named entity. This is what a sane user would probably want, even though
	 * in theory they would be free to define the properties in any order they want.
	 */
	protected List<List<YTypedProperty>> sortIntoTiers(List<YTypedProperty> properties) {
		if (properties.isEmpty()) {
			//Nothing to sort
			return ImmutableList.of();
		} else {
			ImmutableList.Builder<YTypedProperty> primary = ImmutableList.builder();
			ImmutableList.Builder<YTypedProperty> required = ImmutableList.builder();
			ImmutableList.Builder<YTypedProperty> other = ImmutableList.builder();
			for (YTypedProperty p : properties) {
				if (p.isPrimary()) {
					primary.add(p);
				} else if (p.isRequired()) {
					required.add(p);
				} else {
					other.add(p);
				}
			}
			return ImmutableList.of(primary.build(), required.build(), other.build());
		}
	}

	/**
	 * Computes the text that should be appended at the end of a completion
	 * proposal depending on what type of value is expected.
	 */
	protected String appendTextFor(YType type) {
		return new AppendTextBuilder(typeUtil).buildFor(type);
	}

	private List<ICompletionProposal> getValueCompletions(YamlDocument doc, SNode node, int offset, String query) {
		YValueHint[] values=null;
		try {
			values = typeUtil.getHintValues(type, getSchemaContext());
		} catch (Exception e) {
			return ImmutableList.of(completionFactory().errorMessage(query, getMessage(e)));
		}
		if (values!=null) {
			ArrayList<ICompletionProposal> completions = new ArrayList<>();
			YamlIndentUtil indenter = new YamlIndentUtil(doc);
			int referenceIndent;
			try {
				referenceIndent = getContextNode().getIndent();
			} catch (Exception e) {
				//Getting it from the node isn't always correct, but more often than not it is.
				//So this fallback is better than nothing.
				referenceIndent = node.getIndent();
			}
			for (YValueHint value : values) {
				double score = FuzzyMatcher.matchScore(query, value.getValue());
				if (score!=0 && !value.equals(query)) {
					int queryStart = offset-query.length();
					DocumentEdits edits = new DocumentEdits(doc.getDocument());
					edits.delete(queryStart, offset);
					if (!Character.isWhitespace(doc.getChar(queryStart-1))) {
						edits.insert(offset, " ");
					}
					edits.insert(offset, value.getValue());
					String extraInsertion = value.getExtraInsertion();
					if (extraInsertion!=null) {
						edits.insert(offset, indenter.applyIndentation(extraInsertion, referenceIndent));
					}
					completions.add(completionFactory().valueProposal(
							value.getValue(), query, value.getLabel(), type,
							value.getDocumentation(), score, edits, typeUtil
					));
				}
			}
			return completions;
		}
		return Collections.emptyList();
	}

	private String getMessage(Exception _e) {
		Throwable e = ExceptionUtil.getDeepestCause(_e);

		// If value parse exception, do not append any additional information
		if (e instanceof ValueParseException) {
			return ExceptionUtil.getMessageNoAppendedInformation(e);
		} else {
			return ExceptionUtil.getMessage(e);
		}
	}

	@Override
	public YamlAssistContext traverse(YamlPathSegment s) throws Exception {
		if (s.getType()==YamlPathSegmentType.VAL_AT_KEY) {
			if (typeUtil.isSequencable(type) || typeUtil.isMap(type)) {
				return contextWith(s, typeUtil.getDomainType(type));
			}
			String key = s.toPropString();
			Map<String, YTypedProperty> subproperties = typeUtil.getPropertiesMap(type);
			if (subproperties!=null) {
				return contextWith(s, getType(subproperties.get(key)));
			}
		} else if (s.getType()==YamlPathSegmentType.VAL_AT_INDEX) {
			if (typeUtil.isSequencable(type)) {
				return contextWith(s, typeUtil.getDomainType(type));
			}
		}
		return null;
	}

	private YType getType(YTypedProperty prop) {
		if (prop!=null) {
			return prop.getType();
		}
		return null;
	}

	private YamlAssistContext contextWith(YamlPathSegment s, YType nextType) {
		if (nextType!=null) {
			return new YTypeAssistContext(this, contextPath.append(s), nextType, typeUtil);
		}
		return null;
	}


	@Override
	public String toString() {
		return "TypeContext("+contextPath.toPropString()+"::"+type+")";
	}

	@Override
	public Renderable getHoverInfo() {
		if (parent!=null) {
			return parent.getHoverInfo(contextPath.getLastSegment());
		}
		return null;
	}

	public YType getType() {
		return type;
	}

	@Override
	public Renderable getHoverInfo(YamlPathSegment lastSegment) {
		//Hoverinfo is only attached to YTypedProperties so...
		switch (lastSegment.getType()) {
		case VAL_AT_KEY:
		case KEY_AT_KEY:
			YTypedProperty prop = getProperty(lastSegment.toPropString());
			if (prop!=null) {
				return YPropertyInfoTemplates.createHover(contextPath.toPropString(), getType(), prop);
			}
			break;
		default:
		}
		return null;
	}

	protected DynamicSchemaContext getSchemaContext() {
		try {
			SNode contextNode = getContextNode();
			YamlPath fullContextPath = contextPath.prepend(YamlPathSegment.valueAt(documentSelector));
			return new SNodeDynamicSchemaContext(contextNode, fullContextPath);
		} catch (Exception e) {
			Log.log(e);
			return DynamicSchemaContext.NULL;
		}
	}

	@Override
	public Renderable getValueHoverInfo(YamlDocument doc, DocumentRegion documentRegion) {
		//By default we don't provide value-specific hover, so just show the same hover
		// as the assistContext the value is in. This is likely more interesting than showing nothing at all.
		return getHoverInfo();
	}

	private YTypedProperty getProperty(String name) {
		return typeUtil.getPropertiesMap(getType()).get(name);
	}

	protected YamlAssistContext relaxForDashes() {
		try {
			if (typeUtil.isSequencable(type)) {
				YType itemType = typeUtil.getDomainType(type);
				if (itemType!=null) {
					return new YTypeAssistContext(this, itemType) {
						@Override
						public Collection<ICompletionProposal> getCompletions(YamlDocument doc, SNode node, int offset) throws Exception {
							Collection<ICompletionProposal> basicCompletions = super.getCompletions(doc, node, offset);
							return addDashes(basicCompletions, doc, node);
						}
					};
				}
			}
		} catch (Exception e) {
			Log.log(e);
		}
		return null;
	}
	
	protected Collection<ICompletionProposal> getDashedCompletions(YamlDocument doc, SNode current, int offset) {
		try {
			YamlAssistContext relaxed = relaxForDashes();
			if (relaxed!=null) {
				return relaxed.getCompletions(doc, current, offset);
			}
		} catch (Exception e) {
			Log.log(e);
		}
		return ImmutableList.of();
	}

	private Collection<ICompletionProposal> addDashes(Collection<ICompletionProposal> basicCompletions, YamlDocument doc, SNode node) {
		if (!basicCompletions.isEmpty()) {
			List<ICompletionProposal> dashedCompletions = new ArrayList<>(basicCompletions.size());
			for (ICompletionProposal c : basicCompletions) {
				dashedCompletions.add(
					new TransformedCompletion(c) {
						@Override
						protected DocumentEdits transformEdit(DocumentEdits textEdit) {
							textEdit.transformFirstNonWhitespaceEdit((Integer offset, String insertText) -> {
								if (needNewline(textEdit)) {
									return insertText.substring(0,  offset)
											+ "\n" +Strings.repeat(" ", node.getIndent())+"- "
											+ insertText.substring(offset);
								} else if (offset > 2) {
									String prefix = insertText.substring(offset-2, offset);
									if ("  ".equals(prefix)) {
										//special case don't add the "- " in front, but replace the inserted spaces instead.
										return insertText.substring(0, offset-2)+"- "+insertText.substring(offset);
									}
								}
								return insertText.substring(0, offset) + "- "+insertText.substring(offset);
							});
							return textEdit;
						}

						private boolean needNewline(DocumentEdits textEdit) {
							//value proposals which are inserted right after a key will not automatically include a newline, as
							// its not required for them. So we should add it along with the dash.
							try {
								if (original instanceof ValueProposal) {
									Integer insertAt = textEdit.getFirstEditStart();
									if (insertAt!=null) {
										return !"".equals(doc.getLineTextBefore(insertAt).trim());
									}
								}
							} catch (Exception e) {
								Log.log(e);
							}
							return false;
						}

						@Override
						protected String tranformLabel(String originalLabel) {
							return "- "+originalLabel;
						}
					}.deemphasize(DEEMP_DASH_PROPOSAL)
				);
			}
			return dashedCompletions;
		}
		return basicCompletions;
	}

}
/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package apoc.refactor.util;

import java.util.Collections;
import java.util.Map;

import static apoc.util.Util.toBoolean;

/**
 * @author AgileLARUS
 *
 * @since 20-06-17
 */
public class RefactorConfig {

	public enum RelationshipSelectionStrategy {INCOMING, OUTGOING, MERGE}

	public static final String COMBINE = "combine";
	public static final String DISCARD = "discard";
	public static final String OVERWRITE = "overwrite";
	public static final String OVERRIDE = "override";

	private static String MATCH_ALL = ".*";

	private Map<String,String> propertiesManagement = Collections.singletonMap(MATCH_ALL, OVERWRITE);

	private boolean mergeRelsAllowed;
	private boolean mergeVirtualRels;
	private boolean selfRel;
	private boolean createNewSelfRel;
	private boolean preserveExistingSelfRels;
	private boolean countMerge;
	private boolean hasProperties;
	private boolean collapsedLabel;
	private boolean singleElementAsArray;
	private boolean avoidDuplicates;

	private final RelationshipSelectionStrategy relationshipSelectionStrategy;

	public RefactorConfig(Map<String,Object> config) {
		if (config == null) {
			config = Collections.emptyMap();
		}
		this.mergeRelsAllowed = toBoolean(config.get("mergeRels"));
		this.mergeVirtualRels = toBoolean(config.getOrDefault("mergeRelsVirtual", true));
		this.selfRel = toBoolean(config.get("selfRel"));
		this.createNewSelfRel = toBoolean(config.getOrDefault("produceSelfRel", true));
		this.preserveExistingSelfRels = toBoolean(config.getOrDefault("preserveExistingSelfRels", true));
		this.countMerge = toBoolean(config.getOrDefault("countMerge", true));
		this.collapsedLabel = toBoolean(config.get("collapsedLabel"));
		this.singleElementAsArray = toBoolean(config.getOrDefault("singleElementAsArray", false));
		this.avoidDuplicates = toBoolean(config.getOrDefault("avoidDuplicates", false));
		this.relationshipSelectionStrategy = RelationshipSelectionStrategy.valueOf(
				((String) config.getOrDefault("relationshipSelectionStrategy", RelationshipSelectionStrategy.INCOMING.toString())).toUpperCase() );

		Object value = config.get("properties");
		hasProperties = value != null;
		if (value instanceof String) {
			this.propertiesManagement = Collections.singletonMap(MATCH_ALL, value.toString());
		} else if (value instanceof Map) {
			this.propertiesManagement = (Map<String,String>)value;
		}
	}

	public String getMergeMode(String name, boolean isRel) {
		// coherently with https://github.com/neo4j-contrib/neo4j-apoc-procedures/pull/1051
		if (!hasProperties && isRel) {
			return COMBINE;
		}
		for (String key : propertiesManagement.keySet()) {
			if (!key.equals(MATCH_ALL) && name.matches(key)) {
				return propertiesManagement.get(key);
			}
		}
		return propertiesManagement.getOrDefault(name,propertiesManagement.getOrDefault(MATCH_ALL, OVERWRITE));

	}

	public boolean getMergeRelsAllowed(){
		return mergeRelsAllowed;
	}

	public boolean isSelfRel(){ return selfRel; }

	public boolean isCreatingNewSelfRel() {
		return createNewSelfRel;
	}

	public boolean isPreservingExistingSelfRels() {
		return preserveExistingSelfRels;
	}

	public boolean isCountMerge() { return this.countMerge;	}

	public boolean isCollapsedLabel() {
		return collapsedLabel;
	}

	public boolean isMergeVirtualRels() {
		return mergeVirtualRels;
	}

	public boolean isSingleElementAsArray() {
		return singleElementAsArray;
	}

	public boolean isAvoidDuplicates() {
		return avoidDuplicates;
	}

	public RelationshipSelectionStrategy getRelationshipSelectionStrategy() {
		return relationshipSelectionStrategy;
	}
}

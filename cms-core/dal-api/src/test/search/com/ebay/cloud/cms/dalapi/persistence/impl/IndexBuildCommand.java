/*
Copyright [2013-2014] eBay Software Foundation

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/


/**
 * 
 */
/* 
Copyright 2012 eBay Software Foundation 

Licensed under the Apache License, Version 2.0 (the "License"); 
you may not use this file except in compliance with the License. 
You may obtain a copy of the License at 

    http://www.apache.org/licenses/LICENSE-2.0 

Unless required by applicable law or agreed to in writing, software 
distributed under the License is distributed on an "AS IS" BASIS, 
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
See the License for the specific language governing permissions and 
limitations under the License. 
*/ 

package com.ebay.cloud.cms.dalapi.persistence.impl;

import static com.ebay.cloud.cms.dal.entity.AbstractEntityIDHelper.DOT;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ebay.cloud.cms.consts.CMSConsts;
import com.ebay.cloud.cms.dal.persistence.IPersistenceCommand;
import com.ebay.cloud.cms.dal.persistence.MongoExecutor;
import com.ebay.cloud.cms.dal.persistence.PersistenceContext;
import com.ebay.cloud.cms.dal.persistence.PersistenceContext.DBCollectionPolicy;
import com.ebay.cloud.cms.metadata.model.IndexInfo;
import com.ebay.cloud.cms.metadata.model.IndexInfo.IndexOptionEnum;
import com.ebay.cloud.cms.metadata.model.InternalFieldEnum;
import com.ebay.cloud.cms.metadata.model.MetaClass;
import com.ebay.cloud.cms.metadata.model.MetaField;
import com.ebay.cloud.cms.metadata.model.MetaRelationship;
import com.ebay.cloud.cms.metadata.model.internal.BranchMetaClass;
import com.ebay.cloud.cms.metadata.model.internal.HistoryMetaClass;
import com.ebay.cloud.cms.utils.CheckConditions;
import com.ebay.cloud.cms.utils.CollectionUtils;
import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;

/**
 * @author Liangfei(Ralph) Su
 * 
 */
public class IndexBuildCommand implements IPersistenceCommand {

    private final List<MetaClass> metadatas;
    private final boolean onMainBranch;

    public IndexBuildCommand(List<MetaClass> meta, boolean onMainBranch) {
        metadatas = meta;
        this.onMainBranch = onMainBranch;
    }

    @Override
    public void execute(PersistenceContext context) {
        for (MetaClass meta : metadatas) {
            Map<String, DBObject> exsitingIndexMap = getCollectionIndexMap(context, meta);
            Set<String> targetIndexNames = new HashSet<String>(meta.getIndexNames());

            Set<String> deleteSet = CollectionUtils.diffSet(exsitingIndexMap.keySet(), targetIndexNames);
            Set<String> addSet = CollectionUtils.diffSet(targetIndexNames, exsitingIndexMap.keySet());

            //drop indexes
            for (String deleteIndex : deleteSet) {
                // _id_ is a reserved index in mongo
                if ("_id_".equals(deleteIndex)) {
                    continue;
                }
                MongoExecutor.dropIndex(context, meta, deleteIndex);
            }

            //add indexes
            for (String addIndex : addSet) {
                DBObject keyObject = buildIndexKeyObject(meta, meta.getIndexByName(addIndex), onMainBranch);
                DBObject optionObject = buildIndexOptionObject(meta.getIndexByName(addIndex));

                // special collection name for meta history
                if (isHistroyMeta(meta)) {
                    ensureMetaHistoryIndex(context, meta, keyObject, optionObject);
                    continue;
                }

                // special collection name for branch
                if (isBranchMeta(meta)) {
                    ensureBranchIndex(context, meta, keyObject, optionObject);
                    continue;
                }

                MongoExecutor.ensureIndex(context, context.getDBCollection(meta), keyObject, optionObject);
            }

            // // update
            // //TODO: check whether we need to ensure or not: compare between
            // the existing one with the given one
        }
    }

    private boolean isBranchMeta(MetaClass meta) {
        return BranchMetaClass.TYPE_NAME.equals(meta.getName());
    }

    private boolean isHistroyMeta(MetaClass meta) {
        return HistoryMetaClass.NAME.equals(meta.getName());
    }

    private void ensureBranchIndex(PersistenceContext context, MetaClass meta, DBObject keyObject, DBObject optionObject) {
        PersistenceContext branchContext = new PersistenceContext(context.getMetadataService(),
                DBCollectionPolicy.Merged, context.getConsistentPolicy(), CMSConsts.BRANCH_DB_COLL_NAME);
        branchContext.setCollectionFinder(context.getRegistration().collectionFinder);
        branchContext.setMongoDataSource(context.getMongoDataSource());
        MongoExecutor.ensureIndex(branchContext, branchContext.getDBCollection(meta), keyObject,
                optionObject);
    }

    private void ensureMetaHistoryIndex(PersistenceContext context, MetaClass meta, DBObject keyObject,
            DBObject optionObject) {
        PersistenceContext metaHistoryContext = new PersistenceContext(context.getMetadataService(),
                DBCollectionPolicy.Merged, context.getConsistentPolicy(), CMSConsts.METACLASS_HISTORY_COLL);
        metaHistoryContext.setMongoDataSource(context.getMongoDataSource());
        MongoExecutor.ensureIndex(metaHistoryContext, metaHistoryContext.getDBCollection(meta), keyObject,
                optionObject);
    }

    private DBObject buildIndexOptionObject(IndexInfo index) {
        DBObject optionsObject = new BasicDBObject();
        for (IndexOptionEnum option : index.getIndexOptions()) {
            optionsObject.put(option.name(), true);
        }
        optionsObject.put("name", index.getIndexName());
        return optionsObject;
    }

    private DBObject buildIndexKeyObject(MetaClass metadata, IndexInfo index, boolean onMainBranch) {
        DBObject keyObject = new BasicDBObject();
        // in sharded case. sharding key must be prefix of the unique key
        boolean unique = index.getIndexOptions().contains(IndexOptionEnum.unique);
        boolean hashed = index.getIndexOptions().contains(IndexOptionEnum.hashed);
        if (unique && metadata.isMetaSharded()) {
            keyObject.put(InternalFieldEnum.SHARD_KEY.getDbName(), 1);
        }
        for (String key : index.getKeyList()) {
            String[] keySeg = key.split("\\.");
            StringBuilder sb = new StringBuilder();
            getKeyName(metadata, keySeg, 0, sb);
            keyObject.put(sb.toString(), hashed ? "hashed" : 1);
        }

        //since objects in different sub branches are in the same collection, 
        // need to add the branch id into the index.
        // so the objects on different sub branches will not conflict on "unique index"  
        if (!onMainBranch) {
        	keyObject.put(InternalFieldEnum.BRANCH.getDbName(), 1);
        }
        
        return keyObject;
    }

    private void getKeyName(MetaClass metadata, String[] keySeg, int i, StringBuilder sb) {
        if (i >= keySeg.length) {
            return;
        }
        boolean start = (i == 0);
        boolean endSeg = (i == keySeg.length - 1);
        
        if (!start) {
            sb.append(DOT);
        }
        MetaField field = metadata.getFieldByName(keySeg[i]);
        CheckConditions.checkArgument(field != null, "meta class " + metadata.getName() + " does not have field " + keySeg[i]);
        if (field.isInternal()) {
            sb.append(field.getDbName());
        } else if (field instanceof MetaRelationship && endSeg) {
            // if last segment is relationship, we built index on _oid
            sb.append(field.getValueDbName() + DOT + InternalFieldEnum.ID.getDbName());
        } else if (field instanceof MetaRelationship) {
            MetaRelationship rel = (MetaRelationship) field;
            sb.append(field.getValueDbName());
            getKeyName(rel.getRefMetaClass(), keySeg, i + 1, sb);
        } else {
            sb.append(field.getValueDbName());
        }
    }

    private Map<String, DBObject> getCollectionIndexMap(PersistenceContext context, MetaClass metaClass) {
        DBCollection collection = context.getDBCollection(metaClass);
        List<DBObject> indexInfo = collection.getIndexInfo();

        Map<String, DBObject> indexMap = new HashMap<String, DBObject>();
        for (DBObject indexObject : indexInfo) {
            String name = (String) indexObject.get("name");
            indexMap.put(name, indexObject);
        }
        return indexMap;
    }

}

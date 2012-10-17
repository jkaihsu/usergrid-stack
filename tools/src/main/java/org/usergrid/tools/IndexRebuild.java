/*******************************************************************************
 * Copyright 2012 Apigee Corporation
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.usergrid.tools;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import me.prettyprint.cassandra.serializers.ByteBufferSerializer;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usergrid.persistence.Entity;
import org.usergrid.persistence.EntityManager;
import org.usergrid.persistence.EntityRef;
import org.usergrid.persistence.Query;
import org.usergrid.persistence.Results;
import org.usergrid.persistence.SimpleEntityRef;
import org.usergrid.persistence.cassandra.CassandraService;
import org.usergrid.persistence.entities.Application;
import org.usergrid.persistence.exceptions.DuplicateUniquePropertyExistsException;
import org.usergrid.utils.UUIDUtils;

/**
 * This is a utility to load all entities in an application and re-save them,
 * this forces the secondary indexing to be updated.
 * 
 * @author tnine
 * 
 */
public class IndexRebuild extends ToolBase {

    /**
     * 
     */
    private static final String APPLICATION_ARG = "app";

    /**
     * 
     */
    private static final String COLLECTION_ARG = "col";

    /**
     * 
     */
    private static final int PAGE_SIZE = 100;

    public static final ByteBufferSerializer be = new ByteBufferSerializer();

    private static final Logger logger = LoggerFactory
            .getLogger(IndexRebuild.class);

    @Override
    @SuppressWarnings("static-access")
    public Options createOptions() {

        Option hostOption = OptionBuilder.withArgName("host").hasArg()
                .isRequired(true).withDescription("Cassandra host")
                .create("host");

        Option appOption = OptionBuilder.withArgName(APPLICATION_ARG).hasArg()
                .isRequired(false).withDescription("application id or app name")
                .create(APPLICATION_ARG);

        Option collectionOption = OptionBuilder.withArgName(COLLECTION_ARG).hasArg()
                .isRequired(false).withDescription("colleciton name")
                .create(COLLECTION_ARG);

        Options options = new Options();
        options.addOption(hostOption);
        options.addOption(appOption);
        options.addOption(collectionOption);

        return options;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.usergrid.tools.ToolBase#runTool(org.apache.commons.cli.CommandLine)
     */
    @Override
    public void runTool(CommandLine line) throws Exception {
        startSpring();

        logger.info("Starting index rebuild");

        /**
         * Goes through each app id specified
         */
        for (UUID appId : getAppIds(line)) {

            logger.info("Reindexing for app id: {}", appId);

            Set<String> collections = getCollections(line, appId);

            for (String collection : collections) {

                reindex(appId, collection);
            }

        }

        logger.info("Finished index rebuild");

    }

    /**
     * Get all app id
     * 
     * @param line
     * @return
     * @throws Exception
     */
    private Collection<UUID> getAppIds(CommandLine line) throws Exception {
        String appId = line.getOptionValue(APPLICATION_ARG);

        if (appId != null) {
            
            UUID id = UUIDUtils.tryExtractUUID(appId);
            
            if(id == null){
                id = emf.getApplications().get(appId);
            }
            
            return Collections.singleton(id);
        }

        return emf.getApplications().values();

    }

    /**
     * Get collection names. If none are specified, all are returned
     * 
     * @param line
     * @param info
     * @return
     * @throws Exception
     */
    private Set<String> getCollections(CommandLine line, UUID appId)
            throws Exception {

        String passedName = line.getOptionValue(COLLECTION_ARG);

        if (passedName != null) {
            return Collections.singleton(passedName);
        }

        EntityManager em = emf.getEntityManager(appId);

        return em.getApplicationCollections();
    }

    /**
     * The application id. The collection name.
     * 
     * @param appId
     * @param collectionName
     * @throws Exception
     */
    private void reindex(UUID appId, String collectionName) throws Exception {
        logger.info("Reindexing collection: {} for app id: {}", collectionName,
                appId);

        EntityManager em = emf.getEntityManager(appId);
        Application app = em.getApplication();

        // search for all orgs

        Query query = new Query();
        query.setLimit(PAGE_SIZE);
        Results r = null;

        do {

            r = em.searchCollection(app, collectionName, query);

            for (Entity entity : r.getEntities()) {
                logger.info(
                        "Updating entity type: {} with id: {} for app id: {}",
                        new Object[] { entity.getType(), entity.getUuid(),
                                appId });

                try {
                    em.update(entity);
                } catch (DuplicateUniquePropertyExistsException dupee) {
                    logger.error(
                            "duplicate property for type: {} with id: {} for app id: {}.  Property name: {} , value: {}",
                            new Object[] { entity.getType(), entity.getUuid(),
                                    appId, dupee.getPropertyName(),
                                    dupee.getPropertyValue() });
                }

            }

            query.setCursor(r.getCursor());

        } while (r != null && r.size() == PAGE_SIZE);
    }
}

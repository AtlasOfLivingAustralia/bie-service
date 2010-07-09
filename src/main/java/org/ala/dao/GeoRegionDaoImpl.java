/***************************************************************************
 * Copyright (C) 2010 Atlas of Living Australia
 * All Rights Reserved.
 *
 * The contents of this file are subject to the Mozilla Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 ***************************************************************************/
package org.ala.dao;

import javax.inject.Inject;

import org.ala.model.GeoRegion;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.common.SolrInputDocument;
import org.springframework.stereotype.Component;

/**
 * An implementation of the GeoRegionDao interface.
 *
 * @author Dave Martin (David.Martin@csiro.au)
 */
@Component("geoRegionDao")
public class GeoRegionDaoImpl implements GeoRegionDao {

	protected static Logger logger = Logger.getLogger(GeoRegionDaoImpl.class);
	
	protected static final String GR_TABLE = "geoRegion";
	protected static final String GR_COL_FAMILY = "gr";
	
	private static final String GEOREGION_COL = "geoRegion";
	
	protected StoreHelper storeHelper;
	
	@Inject
	protected SolrUtils solrUtils;
	
	/**
	 * @see org.ala.dao.GeoRegionDao#create(org.ala.model.GeoRegion)
	 */
	@Override
	public boolean create(GeoRegion geoRegion) throws Exception {
		if(geoRegion==null){
			throw new IllegalArgumentException("Supplied GeoRegion was null.");
		}
		if(geoRegion.getGuid()==null){
			throw new IllegalArgumentException("Supplied geoRegion has a null Guid value.");
		}
		return storeHelper.putSingle(GR_TABLE, GR_COL_FAMILY, GEOREGION_COL, geoRegion.getGuid(), geoRegion);
	}

	/**
	 * @see org.ala.dao.TaxonConceptDao#createIndex()
	 */
	public void createIndex() throws Exception {
		
		long start = System.currentTimeMillis();
		
        SolrServer solrServer = solrUtils.getSolrServer();
        solrServer.deleteByQuery("idxtype:"+IndexedTypes.REGION); // delete everything!
    	
    	int i = 0;
		
		Scanner scanner = storeHelper.getScanner(GR_TABLE, GR_COL_FAMILY, GEOREGION_COL);
		
		byte[] guidAsBytes = null;
		
		while ((guidAsBytes = scanner.getNextGuid())!=null) {
    		
			String guid = new String(guidAsBytes);
			i++;
			
			if(i%1000==0){
				logger.info("Indexed records: "+i+", current guid: "+guid);
			}
    		
    		//get taxon concept details
			GeoRegion geoRegion = getByGuid(guid);
    		SolrInputDocument doc = new SolrInputDocument();
    		doc.addField("idxtype", IndexedTypes.REGION);
    		doc.addField("id", geoRegion.getGuid());
    		doc.addField("guid", geoRegion.getGuid());
    		doc.addField("name", geoRegion.getName());
    		doc.addField("regionType", geoRegion.getRegionTypeName());
    		doc.addField("acronym", geoRegion.getAcronym());
            solrServer.add(doc);
            solrServer.commit();
    	}
    	long finish = System.currentTimeMillis();
    	logger.info("Index created in: "+((finish-start)/1000)+" seconds with "+ i +" georegions processed.");
	}
	
	/**
	 * @see org.ala.dao.GeoRegionDao#getByGuid(java.lang.String)
	 */
	@Override
	public GeoRegion getByGuid(String guid) throws Exception {
		return (GeoRegion) storeHelper.get(GR_TABLE, GR_COL_FAMILY, GEOREGION_COL, guid, GeoRegion.class);
	}
	/**
	 * @param storeHelper the storeHelper to set
	 */
	public void setStoreHelper(StoreHelper storeHelper) {
		this.storeHelper = storeHelper;
	}

	/**
	 * @param solrUtils the solrUtils to set
	 */
	public void setSolrUtils(SolrUtils solrUtils) {
		this.solrUtils = solrUtils;
	}
}
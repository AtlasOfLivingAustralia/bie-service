/**************************************************************************
 *  Copyright (C) 2010 Atlas of Living Australia
 *  All Rights Reserved.
 *
 *  The contents of this file are subject to the Mozilla Public
 *  License Version 1.1 (the "License"); you may not use this file
 *  except in compliance with the License. You may obtain a copy of
 *  the License at http://www.mozilla.org/MPL/
 *
 *  Software distributed under the License is distributed on an "AS
 *  IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 *  implied. See the License for the specific language governing
 *  rights and limitations under the License.
 ***************************************************************************/
package org.ala.web;

import java.io.*;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.ala.client.appender.RestLevel;
import org.ala.client.model.LogEventType;
import org.ala.client.model.LogEventVO;
import org.ala.dao.DocumentDAO;
import org.ala.dao.FulltextSearchDao;
import org.ala.dao.IndexedTypes;
import org.ala.dao.InfoSourceDAO;
import org.ala.dao.SolrUtils;
import org.ala.dao.TaxonConceptDao;
import org.ala.dto.ExtendedTaxonConceptDTO;
import org.ala.dto.SearchDTO;
import org.ala.dto.SearchResultsDTO;
import org.ala.dto.SearchTaxonConceptDTO;
import org.ala.model.AttributableObject;
import org.ala.model.*;
import org.ala.repository.Predicates;
import org.ala.util.ImageUtils;
import org.ala.util.MimeType;
import org.ala.util.ReadOnlyLock;
import org.ala.util.RepositoryFileUtils;
import org.ala.util.StatusType;
import org.ala.util.WebUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestOperations;

import au.org.ala.checklist.lucene.HomonymException;
import au.org.ala.checklist.lucene.model.NameSearchResult;
import au.org.ala.data.model.LinnaeanRankClassification;
import au.org.ala.data.util.RankType;

import org.ala.model.SynonymConcept;

import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Main controller for the BIE site
 *
 * TODO: If this class gets too big or complex then split into multiple Controllers.
 * Note to TODO: We are already there!
 *
 * @author "Nick dos Remedios <Nick.dosRemedios@csiro.au>"
 */
@Controller(value="speciesController")
public class SpeciesController {
	
    /** Logger initialisation */
    private final static Logger logger = Logger.getLogger(SpeciesController.class);
    /** DAO bean for access to taxon concepts */
    @Inject
    private TaxonConceptDao taxonConceptDao;
    /** DAO bean for access to repository document table */
    @Inject
    private DocumentDAO documentDAO;
    @Inject
    protected InfoSourceDAO infoSourceDAO;
    /** DAO bean for SOLR search queries */
    @Inject
    private FulltextSearchDao searchDao;
    /** Name of view for site home page */
    private String HOME_PAGE = "wsPage";
    /** Name of view for a single taxon */
    private final String SPECIES_SHOW_BRIEF = "species/showBrief";	
    /** Name of view for list of pest/conservation status */
    private final String STATUS_LIST = "species/statusList";
    @Inject
    protected RepoUrlUtils repoUrlUtils;
    /** The set of data sources that will not be truncated **/
    protected Set<String> nonTruncatedSources = new java.util.HashSet<String>();
    /** The set of data sources that have low priority (ie displayed at the end of the list or removed if other sources available) **/
    protected Set<String> lowPrioritySources = new java.util.HashSet<String>();
    /** The URI for JSON data for static occurrence map */
    private final String SPATIAL_JSON_URL = "http://spatial.ala.org.au/alaspatial/ws/density/map?species_lsid=";

    public static final String COL_HOME = "http://www.catalogueoflife.org/";
    public static final String APNI_HOME = "http://www.anbg.gov.au/apni/";
    public static final String AFD_HOME = "http://www.environment.gov.au/biodiversity/abrs/online-resources/fauna/afd/home";

    /** Max width to Truncate text to */
    private int TEXT_MAX_WIDTH = 200;

    InfoSource afd;
    InfoSource apni;
    InfoSource col;

    public SpeciesController(){
        nonTruncatedSources.add("http://www.environment.gov.au/biodiversity/abrs/online-resources/flora/main/index.html");
        lowPrioritySources.add("http://www.ozanimals.com/");
        lowPrioritySources.add("http://en.wikipedia.org/");
    }

    @PostConstruct
    public void init(){
        afd = infoSourceDAO.getByUri(AFD_HOME);
        apni = infoSourceDAO.getByUri(APNI_HOME);
        col = infoSourceDAO.getByUri(COL_HOME);
    }

    /**
     * Custom handler for the welcome view.
     * <p>
     * Note that this handler relies on the RequestToViewNameTranslator to
     * determine the logical view name based on the request URL: "/welcome.do"
     * -&gt; "welcome".
     *
     * @return viewname to render
     */
    @RequestMapping("/")
    public String homePageHandler() {
        return HOME_PAGE;
    }

    /**
     * Map to a /{guid} URI.
     * E.g. /species/urn:lsid:biodiversity.org.au:afd.taxon:a402d4c8-db51-4ad9-a72a-0e912ae7bc9a
     * 
     * @param guid
     * @return view name
     * @throws Exception
     */ 
    @Deprecated
    @RequestMapping(value = "/species/charts/{guid:.+}*", method = RequestMethod.GET)
    public String showChartInfo(@PathVariable("guid") String guid,
            HttpServletResponse response) throws Exception {
        String url = null;
        if(guid.trim().endsWith(".json")){
        	url = "http://biocache.ala.org.au/ws/occurrences/taxon/" + guid;
        }
        else{
        	url = "http://biocache.ala.org.au/ws/occurrences/taxon/" + guid + ".json";
        }
        String contentAsString = WebUtils.getUrlContentAsString(url);
        response.setContentType("application/json");
        response.getWriter().write(contentAsString);
        return null;
    }

    /**
     * Map to a /{guid}.json or /{guid}.xml URI.
     * E.g. /species/urn:lsid:biodiversity.org.au:afd.taxon:a402d4c8-db51-4ad9-a72a-0e912ae7bc9a
     *
     * @param guid
     * @param model
     * @return view name
     * @throws Exception
     */
    @RequestMapping(value = {"/species/shortProfile/{guid}.json","/species/shortProfile/{guid}.xml"}, method = RequestMethod.GET)
    public void showInfoSpecies(
            @PathVariable("guid") String guid, Model model) throws Exception {

        ExtendedTaxonConceptDTO edto = taxonConceptDao.getExtendedTaxonConceptByGuid(guid);

        if(edto!=null){

            repoUrlUtils.fixRepoUrls(edto);

            model.addAttribute("scientificName", edto.getTaxonConcept().getNameString());

            model.addAttribute("year", edto.getTaxonConcept().getAuthorYear()); //to be added - currently blank
            model.addAttribute("scientificNameAuthorship", edto.getTaxonConcept().getAuthor());
            model.addAttribute("rank", edto.getTaxonConcept().getRankString());
            model.addAttribute("rankID", edto.getTaxonConcept().getRankID());

            if(edto.getTaxonName()!=null){
                model.addAttribute("author", edto.getTaxonName().getAuthorship());
            } else {
                model.addAttribute("author", edto.getTaxonConcept().getAuthor());
            }

            if(edto.getClassification()!=null){
                model.addAttribute("family", edto.getClassification().getFamily());
                model.addAttribute("kingdom", edto.getClassification().getKingdom());
            }
            model.addAttribute("year", edto.getTaxonConcept().getAuthorYear());

            if(!edto.getCommonNames().isEmpty()){
                CommonName cn = edto.getCommonNames().get(0);
                model.addAttribute("commonName", cn.getNameString());
                model.addAttribute("commonNameGUID", cn.getGuid());
            }
            if(!edto.getImages().isEmpty()){
                Image image = edto.getImages().get(0);
                model.addAttribute("imageURL", image.getRepoLocation());
                model.addAttribute("thumbnail", image.getThumbnail());
                //Issue http://code.google.com/p/ala/issues/detail?id=295 
                model.addAttribute("imageAttribution", image.getCreator());
                model.addAttribute("imageLicense", image.getLicence());
                model.addAttribute("imageMetadataUrl", image.getDcLocation());
            }
        }
    }

    public TaxonDTO createTaxonDTO(ExtendedTaxonConceptDTO edto){
        if(edto.getTaxonConcept() !=null){
            TaxonDTO t = new TaxonDTO();
            t.setGuid(edto.getTaxonConcept().getGuid());
            t.setScientificName(edto.getTaxonConcept().getNameString());
            t.setTaxonInfosourceURL(edto.getTaxonConcept().getInfoSourceURL());
            t.setTaxonInfosourceName(edto.getTaxonConcept().getInfoSourceName());
            t.setYear(edto.getTaxonConcept().getAuthorYear());
            t.setScientificNameAuthorship(edto.getTaxonConcept().getAuthor());
            t.setRank(edto.getTaxonConcept().getRankString());
            t.setRankID(edto.getTaxonConcept().getRankID());
            if(edto.getTaxonName()!=null){
                t.setAuthor(edto.getTaxonName().getAuthorship());
            } else {
                t.setAuthor(edto.getTaxonConcept().getAuthor());
            }

            if(edto.getClassification()!=null){
                t.setFamily(edto.getClassification().getFamily());
                t.setKingdom(edto.getClassification().getKingdom());
            }
            if(!edto.getCommonNames().isEmpty()){
                CommonName cn = edto.getCommonNames().get(0);
                t.setCommonName(cn.getNameString());
                t.setCommonNameGuid(cn.getGuid());
            }
            if(!edto.getImages().isEmpty()){
                Image image = edto.getImages().get(0);
                t.setImageURL(image.getRepoLocation());
                t.setThumbnail(image.getThumbnail());
                t.setImageRights(image.getRights());
                t.setImageCreator(image.getCreator());
                t.setImageisPartOf(image.getIsPartOf());
                t.setImageInfosourceName(image.getInfoSourceName());
                t.setImageInfosourceURL(image.getInfoSourceURL());
            }
            return t;
        } else {
            return null;
        }
    }

    /**
     * Get the list of collections, institutes, data resources and data providers that have specimens for the supplied taxon concept guid
     * Wrapper around the biocache service: http://biocache.ala.org.au/occurrences/sourceByTaxon
     * @param guid
     * @param response
     * @throws Exception
     */
    @Deprecated
    @RequestMapping(value = "/species/source/{guid:.+}*", method = RequestMethod.GET)
    public void showSourceInfo(@PathVariable("guid") String guid,
            HttpServletResponse response) throws Exception {
        String url = null;
        if(guid.trim().endsWith(".json")){
        	url = "http://biocache.ala.org.au/ws/occurrences/taxon/source/" + guid;
        }
        else{
        	url = "http://biocache.ala.org.au/ws/occurrences/taxon/source/" + guid + ".json";
        }
        String contentAsString = WebUtils.getUrlContentAsString(url);
        response.setContentType("application/json");
        response.getWriter().write(contentAsString);
    }

    /**
     * Retrieve a list of identifiers for each of the names supplied.
     * 
     * @param scientificNames
     * @return
     * @throws Exception
     */
    @RequestMapping(value = {"/ws/guid/batch", "/guid/batch"}, method = RequestMethod.GET)
    public @ResponseBody Map<String,List<GuidLookupDTO>> getGuidForNames(
            @RequestParam("q") List<String> scientificNames) throws Exception {
        Map<String,List<GuidLookupDTO>> nameMaps = new LinkedHashMap<String,List<GuidLookupDTO>>();
        for(String name: scientificNames){
            nameMaps.put(name, findGuids(name));
        }
        return nameMaps;
    }

    /**
     * Map to a /{guid} URI.
     * E.g. /species/urn:lsid:biodiversity.org.au:afd.taxon:a402d4c8-db51-4ad9-a72a-0e912ae7bc9a
     * 
     * @param scientificName
     * @return view name
     * @throws Exception
     */
    @RequestMapping(value = {"/ws/guid/{scientificName:.+}","/guid/{scientificName:.+}"}, method = RequestMethod.GET)
    public @ResponseBody List<GuidLookupDTO> getGuidForName(@PathVariable("scientificName") String scientificName) throws Exception {
        return findGuids(scientificName);
    }
    
    private void logTime(long start, String message){
        long numMs = System.currentTimeMillis() - start;
        logger.debug(message + " took " + numMs + "ms. " + numMs/1000 + " sec. " + numMs/60000 + " mins" );
    }
    
    @RequestMapping(value ={"/ws/classification/{value:.+}*","/classification/{value:.+}*"}, method = RequestMethod.GET)
    public @ResponseBody List<Map<String,String>> getClassification(@PathVariable("value") String value) throws Exception{
        //determine whether the value is a scientific name or lsid that needs to be resolved.
        long start = System.currentTimeMillis();
        ExtendedTaxonConceptDTO etc =findConceptByNameOrGuid(value);
        logTime(start, "Retrieve ETC");
        if(etc != null && etc.getTaxonConcept() != null){
            TaxonConcept tc = etc.getTaxonConcept();
            if(tc != null && tc.getLeft() != null && tc.getRight() != null){
              SearchResultsDTO<SearchTaxonConceptDTO> taxonHierarchy = searchDao.getClassificationByLeftNS(tc.getLeft(), tc.getRight());
              logTime(start, "Obtain Classification");
              List<Map<String,String>> list = new java.util.LinkedList<Map<String,String>>();
              for(SearchTaxonConceptDTO stc :taxonHierarchy.getResults()){
                  Map<String, String> map = new java.util.LinkedHashMap<String,String>();
                  String name = StringUtils.isEmpty(stc.getNameComplete())? stc.getName() : stc.getNameComplete();
                  map.put("scientificName", name);
                  map.put("guid", stc.getGuid());
                  map.put("rank", stc.getRank());
                  if(stc.getRawRank() != null)
                      map.put("rawRank",stc.getRawRank());
                  list.add(map);
              }
              logTime(start,"Process Classification");
              return list;
            }
        }
        return Collections.EMPTY_LIST;
    }

    /**
     * Webservice to find child concepts for a given taxon concept
     *
     * @param value
     * @return
     * @throws Exception
     */
    @RequestMapping(value ={"/ws/childConcepts/{value:.+}*", "/childConcepts/{value:.+}*"}, method = RequestMethod.GET)
    public @ResponseBody List<SearchTaxonConceptDTO> getChildConceptsForTaxa(@PathVariable("value") String value) throws Exception {
        List<SearchTaxonConceptDTO> childConcepts = new ArrayList<SearchTaxonConceptDTO>();
        ExtendedTaxonConceptDTO etc = findConceptByNameOrGuid(value);

        if (etc != null && etc.getTaxonConcept() != null){
            TaxonConcept tc = etc.getTaxonConcept();
            childConcepts = searchDao.getChildConceptsParentId(Integer.toString(tc.getId()));
            logger.debug("childConcepts for " + tc.getId() + " = " + childConcepts);
        }

        return childConcepts;
    }

    private List<GuidLookupDTO> findGuids(String scientificName) throws Exception {
        String lsid = getLsidByNameAndKingdom(scientificName);
        List<GuidLookupDTO> others = new ArrayList<GuidLookupDTO>();
        List<GuidLookupDTO> guids = new ArrayList<GuidLookupDTO>(1);
        if(lsid != null && lsid.length() > 0){
            ExtendedTaxonConceptDTO etc = taxonConceptDao.getExtendedTaxonConceptByGuid(lsid, true, true);
            if (etc.getTaxonConcept() != null && etc.getTaxonConcept().getGuid() != null) {

                //FIXME - this should come straight from BIE with a the attribution
                //coming from the BIE. The identifier should be a separate model object
                //which extends AttributableObject
                TaxonConcept tc = etc.getTaxonConcept();

                GuidLookupDTO preferredGuid = new GuidLookupDTO();
                preferredGuid.setIdentifier(tc.getGuid());
                preferredGuid.setInfoSourceId(tc.getInfoSourceId());
                preferredGuid.setInfoSourceURL(tc.getInfoSourceURL());
                preferredGuid.setInfoSourceName(tc.getInfoSourceName());
                preferredGuid.setName(tc.getNameString());
                //guids.add(preferredGuid);

                //add identifiers
                for(String identifier: etc.getIdentifiers()){
                    GuidLookupDTO g = new GuidLookupDTO();
                    g.setIdentifier(identifier);
                    g.setName(tc.getNameString());
                    //FIXME to be removed
                    if(identifier.contains(":apni.")){
                        g.setInfoSourceId(Integer.toString(apni.getId()));
                        g.setInfoSourceURL(apni.getWebsiteUrl());
                        g.setInfoSourceName(apni.getName());
                        others.add(g);
                    } else if(identifier.contains(":afd.")){
                        g.setInfoSourceId(Integer.toString(afd.getId()));
                        g.setInfoSourceURL(afd.getWebsiteUrl());
                        g.setInfoSourceName(afd.getName());
                        others.add(g);
                    } else if(identifier.contains(":catalogueoflife.")){
                        g.setInfoSourceId(Integer.toString(col.getId()));
                        g.setInfoSourceURL(col.getWebsiteUrl());
                        g.setInfoSourceName(col.getName());
                        others.add(g);
                    }
                }
                if(!tc.getGuid().equals(lsid)){
                    //we matched to a synonym locate the synonym
                    List<SynonymConcept> synonyms =etc.getSynonyms();
                    SynonymConcept matchedConcept =null;
                    for(SynonymConcept s: synonyms){
                       if(s.getGuid().equals(lsid)){
                           matchedConcept = s;
                           break;
                       }                      
                    }
                    if(matchedConcept != null){
                        GuidLookupDTO synonymGuid = new GuidLookupDTO();
                        synonymGuid.setIdentifier(matchedConcept.getGuid());
                        synonymGuid.setInfoSourceId(matchedConcept.getInfoSourceId());
                        synonymGuid.setInfoSourceURL(matchedConcept.getInfoSourceURL());
                        synonymGuid.setInfoSourceName(matchedConcept.getInfoSourceName());
                        synonymGuid.setName(matchedConcept.getNameString());
                        //include the current list as accepted guids for the name
                        synonymGuid.setOtherGuids(others.toArray(new GuidLookupDTO[]{}));
                        synonymGuid.setAcceptedIdentifier(preferredGuid.getIdentifier());
                        synonymGuid.setAcceptedName(preferredGuid.getName());
                        guids.add(synonymGuid);
                    }
                }
                else{
                    preferredGuid.setAcceptedIdentifier(preferredGuid.getIdentifier());
                    preferredGuid.setAcceptedName(preferredGuid.getName());
                    preferredGuid.setOtherGuids(others.toArray(new GuidLookupDTO[]{}));
                    guids.add(preferredGuid);
                }
            }
        }
        return guids;
    }

    /**
     * Get the repo location of a image according to {guid} .
     * E.g. /species/image/urn:lsid:biodiversity.org.au:afd.taxon:a402d4c8-db51-4ad9-a72a-0e912ae7bc9a
     * 
     * @return view name
     * @throws Exception
     */ 
    @RequestMapping(value = {"/species/image/{imageType}/{guid:.+}", "/ws/species/image/{imageType}/{guid:.+}"}, method = RequestMethod.GET)
    public String showImages(
            @PathVariable("guid") String guidParam,
            @PathVariable("imageType") String imageType,
            @RequestParam(value="showNoImage", defaultValue = "true", required=false) boolean showNoImage,
            HttpServletResponse response
    ) throws Exception {
        String guid = guidParam;
        logger.debug("Displaying image for: " + guid +" .....");

        SearchResultsDTO<SearchDTO> stcs = searchDao.findByName(IndexedTypes.TAXON, guid, null, 0, 1, "score", "desc");
        //search by name
        if(stcs.getTotalRecords() == 0){
            logger.debug("Searching with by name instead....");
            stcs = (SearchResultsDTO<SearchDTO>) searchDao.findByScientificName(guid,1);
        }

        if(stcs.getTotalRecords()>0){
            SearchTaxonConceptDTO st = (SearchTaxonConceptDTO) stcs.getResults().get(0);
            repoUrlUtils.fixRepoUrls(st);
//            logger.info(st.getImage());
            if ("thumbnail".equals(imageType) && st.getThumbnail() != null && !"".equals(st.getThumbnail())) {
                return "redirect:" + st.getThumbnail();
            } else if ("small".equals(imageType) && st.getImage() != null && !"".equals(st.getImage())) {
                return "redirect:" + st.getImage().replaceAll("raw", "smallRaw");
            }  else if ("large".equals(imageType) && st.getImage() != null && !"".equals(st.getImage())) {
                return "redirect:" + st.getImage().replaceAll("raw", "largeRaw");
            }  else if(st.getImage() !=null) {
                return "redirect:" + st.getImage();
            }
        }

        if(showNoImage){
            return "redirect:/images/noImage85.jpg"; // no image
        } else {
            response.sendError(404);
            return null;
        }
    }
    /**
     * Performs a bulk species lookup by providing list of species in a JSON map with a key "names"
     * @param request
     * @param response
     * @return
     * @throws Exception
     */
    @RequestMapping(value = {"/species/lookup/bulk","/ws/species/lookup/bulk"}, method = RequestMethod.POST)
    @ResponseBody public SearchDTO[] bulkSpeciesLookup(HttpServletRequest request, HttpServletResponse response) throws Exception{
        //input stream should be a JSON map of values with a mandatory value for names
        ObjectMapper om = new ObjectMapper();
        Map<String,String> map = new HashMap<String,String>();
        if(request.getContentLength()>0){
            try{
                map = om.readValue(request.getInputStream(),new TypeReference<HashMap<String,String>>(){});
                if(map.containsKey("names")){
                    boolean handleNulls =true;
                    //extract the guids
                    String[] guids = om.readValue(map.get("names").toString(), (new String[0]).getClass());
                    return performSpeciesBulklookup(guids, handleNulls);
                } else{
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unable to perform a bulklookup without a list of names");
                    return null;
                }
            } catch (Exception e){
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Format of input incorrect: " + e.getMessage());
                return null;
            }
        } else {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unable to perform a bulklookup without request data");
            return null; 
        }
    }
    /**
     * Common code to perform the species bulklookup for the new and deprecated webservice.
     * @param guids
     * @param handleNulls
     * @return
     * @throws Exception
     */
    private SearchDTO[] performSpeciesBulklookup(String guids[], boolean handleNulls) throws Exception{
        List<SearchDTO> resultSet = new ArrayList<SearchDTO>();
        for(int i=0; i< guids.length; i++){
            //Need to sort the scores descended to get the highest score first
            SearchResultsDTO<SearchDTO> results = searchDao.doExactTextSearch(guids[i], null, 0, 1, "score", "desc"); 
            //if there is no exact scientific name attempt to find by name.
            if(results.getResults().isEmpty()){
                results = searchDao.findByName(IndexedTypes.TAXON, guids[i], null, 0, 1, "score", "desc");
                
            }
            if(results.getTotalRecords() > 0){
                repoUrlUtils.fixRepoUrls(results);
                resultSet.addAll(results.getResults());
            } else if(handleNulls){
                resultSet.add(null);
            }
        }
        return resultSet.toArray(new SearchDTO[0]);
        
    }
    
    /**
     * TODO Replace this with a more efficient query mechanism.
     * 
     * @param request
     * @return
     * @throws Exception
     * @deprecated Due to the fact that there is no way to align the results to the requested name because name not 
     * found are not included. Use {@link #bulkSpeciesLookup()} instead
     */
    @Deprecated
    @RequestMapping(value = {"/species/bulklookup.json","/ws/species/bulklookup.json"}, method = RequestMethod.POST)
    public SearchDTO[] bulkImageLookup(HttpServletRequest request, HttpServletResponse response) throws Exception {
        ObjectMapper om = new ObjectMapper();
        InputStream is = request.getInputStream();
        if(request.getContentLength()>0){
            try{
                String[] guids = om.readValue(is, (new String[0]).getClass());
                return performSpeciesBulklookup(guids, false);

            } catch (Exception e){
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Format of input incorrect: " + e.getMessage());
                return null;
            }
        } else{
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unable to perform a bulklookup without a list of species");
            return null;
        }
    }
    /**
     * A more efficient ws for looking up a batch of guids.
     *
     * @param request
     * @return
     * @throws Exception
     */
    @RequestMapping(value = {"/species/guids/bulklookup.json","/ws/species/guids/bulklookup.json"}, method = RequestMethod.POST)
    public SearchDTO[] bulkImageLookupBasedOnGuids(HttpServletRequest request, HttpServletResponse response) throws Exception {
        ObjectMapper om = new ObjectMapper();
        InputStream is = request.getInputStream();
        if(request.getContentLength()>0){
            String[] guids = om.readValue(is, (new String[0]).getClass());
            SearchDTO[] results= searchDao.findByGuids(guids).getResults().toArray(new SearchDTO[]{});
            repoUrlUtils.fixRepoUrls(results);
            return results;
        } else{
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unable to perform a bulklookup without a list of guids");
            return null;
        }
    }

    /**
     * WS - return JSON list of images for a given higher taxa name string and rank
     *
     * @param taxonRank
     * @param scientificName
     * @return
     */
    @RequestMapping(value = {"/ws/higherTaxa/images.json", "/higherTaxa/images.json"}, method = RequestMethod.GET)
    public List<SearchDTO> taxaImageSearch(@RequestParam(value="taxonRank", required=true) String taxonRank,
                @RequestParam(value="scientificName", required=true) String scientificName) {
        return imageSearch(taxonRank, scientificName);
    }

    private List<SearchDTO> imageSearch(String taxonRank, String scientificName){
		List<String> filterQueries = new ArrayList<String>();
		filterQueries.add("idxtype:TAXON");
		filterQueries.add("hasImage:true");
        filterQueries.add("rank:species");
        filterQueries.add("australian_s:recorded");

        if("order".equals(taxonRank)){
            taxonRank  = "bioOrder";
        }

		filterQueries.add(taxonRank+":"+scientificName);

		List<SearchDTO> images = null;
		try {
			SearchResultsDTO<SearchDTO> results = searchDao.doFullTextSearch(null, (String[])
                    filterQueries.toArray(new String[0]), (String[]) null, 0, 10, "score", "asc");
			results = repoUrlUtils.fixRepoUrls(results);
			images = results.getResults();
		} catch (Exception e) {
			images = new ArrayList<SearchDTO>();
			logger.error("imageSearch: " + e);
		}		
		return images;    	
    }
    
    /**
     * Checks to see if the supplied name is a kingdom
     * @param name
     * @return
     */
    private boolean isKingdom(String name){
        //String lsid = taxonConceptDao.findLsidByName(name, "kingdom");
        try{
            NameSearchResult nsr = taxonConceptDao.findCBDataByName(name,null, "kingdom");
            return nsr != null && nsr.getRank() == RankType.KINGDOM;
        }
        catch(Exception e){
            return false;
        }
    }
    /**
     * Splits up a url into scientific name and kingdom
     * http://bie.ala.org.au/species/Scatochresis episema (Animalia)
     * 
     * But will cater for URLs that contain a subgenus
     * http://bie.ala.org.au/species/Pulex (Pulex)
     * 
     * http://bie.ala.org.au/species/Pulex (Pulex) (Animalia)
     * 
     * @param in
     * @return
     */
    private String[] extractComponents(String in){
        String[] retArray = new String[2];
        int lastOpen =in.lastIndexOf("(");
        int lastClose = in.lastIndexOf(")"); 
        if(lastOpen < lastClose){
            //check to see if the last brackets are a kingdom
            String potentialKingdom = in.substring(lastOpen+1, lastClose);
            if(isKingdom(potentialKingdom)){
                retArray[0] = in.substring(0, lastOpen);
                retArray[1] = potentialKingdom;
            }
            else{
                retArray[0] = in;
            }
        }
        else{
            retArray[0] = in;
            //kingdom is null
        }
        return retArray;
        
    }

    
    private String getLsidByNameAndKingdom(String parameter){
      String lsid = null;
      String name = null;
      String kingdom = null;
      
      String[] parts = extractComponents(parameter);
      name = parts[0];
      name = name.replaceAll("_", " ");
      name = name.replaceAll("\\+", " ");
      kingdom = parts[1];
      if(kingdom != null){
          LinnaeanRankClassification cl = new LinnaeanRankClassification(kingdom, null);
          cl.setScientificName(name);             
          lsid = taxonConceptDao.findLsidByName(cl.getScientificName(), cl, null);
      }
      //check for a scientific name first - this will lookup in the name matching index.  This will produce the correct result in a majority of scientific name cases.
      if(lsid == null || lsid.length() < 1){            
          lsid = taxonConceptDao.findLsidForSearch(name, true);
      }

      if(lsid == null || lsid.length() < 1){
          lsid = taxonConceptDao.findLSIDByCommonName(name);
      }

      if(lsid == null || lsid.length() < 1){
          lsid = taxonConceptDao.findLSIDByConcatName(name);
      }

      
      return lsid;
  }

    /**
     * Map to a /{guid}.json or /{guid}.xml URI.
     * E.g. /species/urn:lsid:biodiversity.org.au:afd.taxon:a402d4c8-db51-4ad9-a72a-0e912ae7bc9a
     * 
     * @param guid
     * @param model
     * @return view name
     * @throws Exception
     */ 
    @RequestMapping(value = {"/species/info/{guid}.json","/species/info/{guid}.xml"}, method = RequestMethod.GET)
    public SearchResultsDTO showInfoSpecies(
            @PathVariable("guid") String guid,
            @RequestParam(value="conceptName", defaultValue ="", required=false) String conceptName,
            Model model) throws Exception {
        //sort by score needs to be desc.
        SearchResultsDTO<SearchDTO> stcs = searchDao.findByName(IndexedTypes.TAXON, guid, null, 0, 1, "score", "desc");
        if(stcs.getTotalRecords()>0){
            SearchTaxonConceptDTO st = (SearchTaxonConceptDTO) stcs.getResults().get(0);
            model.addAttribute("taxonConcept", repoUrlUtils.fixRepoUrls(st));
        }

        return stcs;
    }

    /**
     * Map to a /{guid}.json or /{guid}.xml URI.
     * E.g. /species/urn:lsid:biodiversity.org.au:afd.taxon:a402d4c8-db51-4ad9-a72a-0e912ae7bc9a
     * 
     * @param guid
     * @param model
     * @return view name
     * @throws Exception
     */ 
    @RequestMapping(value = {"/species/moreInfo/{guid}.json","/species/moreInfo/{guid}.xml"}, method = RequestMethod.GET)
    public String showMoreInfoSpecies(
            @PathVariable("guid") String guid,
            @RequestParam(value="conceptName", defaultValue ="", required=false) String conceptName,
            Model model) throws Exception {
        guid = taxonConceptDao.getPreferredGuid(guid);
        model.addAttribute("taxonConcept", taxonConceptDao.getByGuid(guid));
        model.addAttribute("commonNames", taxonConceptDao.getCommonNamesFor(guid));
        model.addAttribute("images", repoUrlUtils.fixRepoUrls(taxonConceptDao.getImages(guid)));
        return SPECIES_SHOW_BRIEF;
    }

    /**
     * JSON output for TC guid
     *
     * @param guid
     * @return
     * @throws Exception
     */
    @RequestMapping(value = {"/species/{guid:.+}","/ws/species/{guid:.+}", "/ws/species/{guid}.json","/species/{guid}.json","/species/{guid}.jsonp"}, method = RequestMethod.GET)
    public @ResponseBody ExtendedTaxonConceptDTO showSpeciesJson(@PathVariable("guid") String guid) throws Exception {
        logger.info("Retrieving concept JSON with guid: "+guid);
        return findConceptByNameOrGuid(guid);
    }

    /**
     * XML output for TC guid
     *
     * @param guid
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/species/{guid}.xml", method = RequestMethod.GET)
    public ExtendedTaxonConceptDTO showSpeciesXml(@PathVariable("guid") String guid) throws Exception {
        logger.info("Retrieving concept XML with guid: "+guid);
        return findConceptByNameOrGuid(guid);
    }

    private ExtendedTaxonConceptDTO findConceptByNameOrGuid(String guid) throws Exception {
        ExtendedTaxonConceptDTO etc = taxonConceptDao.getExtendedTaxonConceptByGuid(guid);
        if(etc==null || etc.getTaxonConcept()==null || etc.getTaxonConcept().getNameString()==null){
            String lsid = getLsidByNameAndKingdom(guid);
            if(lsid != null && lsid.length() > 0){
                etc = taxonConceptDao.getExtendedTaxonConceptByGuid(lsid);
                return repoUrlUtils.fixRepoUrls(etc);
            }
        } else {
            return repoUrlUtils.fixRepoUrls(taxonConceptDao.getExtendedTaxonConceptByGuid(guid));
        }
        return null;
    }

    /**
     * JSON web service (AJAX) to return details for a repository document
     *
     * @param documentId
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/species/document/{documentId}.json", method = RequestMethod.GET)
    public Document getDocumentDetails(@PathVariable("documentId") int documentId) throws Exception {
        Document doc = documentDAO.getById(documentId);

        if (doc != null) {
            // augment data with title from reading dc file
            String fileName = doc.getFilePath()+"/dc";
            RepositoryFileUtils repoUtils = new RepositoryFileUtils();
            List<String[]> lines = repoUtils.readRepositoryFile(fileName);
            //System.err.println("docId:"+documentId+"|filename:"+fileName);
            for (String[] line : lines) {
                // get the dc.title value
                if (line[0].endsWith(Predicates.DC_TITLE.getLocalPart())) {
                    doc.setTitle(line[1]);
                } else if (line[0].endsWith(Predicates.DC_IDENTIFIER.getLocalPart())) {
                    doc.setIdentifier(line[1]);
                }
            }
        }
        return doc;
    }

    /**
     * Dynamically generate a scaled and/or square image
     * 
     * @param documentId
     * @param scale
     * @param square
     * @param outputStream
     * @param response
     * @throws IOException
     */
//    @RequestMapping(value="/species/images/{documentId}.jpg", method = RequestMethod.GET)
    public void thumbnailHandler(@PathVariable("documentId") int documentId, 
            @RequestParam(value="scale", required=false, defaultValue ="1000") Integer scale,
            @RequestParam(value="square", required=false, defaultValue ="false") Boolean square,
            OutputStream outputStream,
            HttpServletResponse response) throws IOException {
        logger.debug("Requested image " + documentId + ".jpg ");
        Document doc = documentDAO.getById(documentId);
        if (doc != null) {
            // augment data with title from reading dc file
            MimeType mt = MimeType.getForMimeType(doc.getMimeType());
            String fileName = doc.getFilePath()+"/raw"+mt.getFileExtension();
            logger.debug("filename = "+fileName);
            ImageUtils iu = new ImageUtils();
            try {
                logger.debug("Loading with image utils...");
                iu.load(fileName); // problem with Jetty 7.0.1
                logger.debug("Loaded");
                // create a square image (crops)
                if (square) {
                    logger.debug("Producing square images...");
                    iu.square();
                }
                // scale if original is bigger than "scale" pixels
                boolean success = iu.smoothThumbnail(scale);
                logger.debug("Thumbnailing success..."+success);
                if(success){
                    logger.debug("Thumbnailing success...");
                    response.setContentType(mt.getMimeType());
                    ImageIO.write(iu.getModifiedImage(), mt.name(), outputStream);
                } else {
                    logger.debug("Fixing URL for file name: " +fileName+", "+repoUrlUtils);
                    String url = repoUrlUtils.fixSingleUrl(fileName);
                    logger.debug("Small image detected, redirecting to source: " +url);
                    response.sendRedirect(repoUrlUtils.fixSingleUrl(url));
                    return;
                }
            } catch (Exception ex) {
                logger.error("Problem loading image with JAI: " + ex.getMessage(), ex);
                String url = repoUrlUtils.fixSingleUrl(fileName);
                logger.warn("Redirecting to: "+url);
                response.sendRedirect(repoUrlUtils.fixSingleUrl(url));
                return;
            }
        } else {
            logger.error("Requested image " + documentId + ".jpg was not found");
            response.sendError(response.SC_NOT_FOUND, "Requested image " + documentId + ".jpg was not found");
        }
    }

    /**
     * Dynamically generate a scaled and/or square image
     * 
     * @param documentId
     * @param scale
     * @param square
     * @param outputStream
     * @param response
     * @throws IOException
     */
    @RequestMapping(value="/species/images/{documentId}.jpg", method = RequestMethod.GET)
    public void largeRawHandler(@PathVariable("documentId") int documentId, 
            @RequestParam(value="scale", required=false, defaultValue ="1000") Integer scale,
            @RequestParam(value="square", required=false, defaultValue ="false") Boolean square,
            OutputStream outputStream,
            HttpServletResponse response) throws IOException {
        logger.debug("Requested image " + documentId + ".jpg ");
        ImageUtils iu = new ImageUtils();
        Document doc = documentDAO.getById(documentId);
        if (doc != null) {
            // augment data with title from reading dc file
            MimeType mt = MimeType.getForMimeType(doc.getMimeType());
            String fileName = doc.getFilePath()+"/largeRaw"+mt.getFileExtension();
            logger.debug("filename = "+fileName);            
            try {
                logger.debug("Loading with image utils...");
                iu.load(fileName); // problem with Jetty 7.0.1
                logger.debug("Loaded");                
                response.setContentType(mt.getMimeType());
                ImageIO.write(iu.getModifiedImage(), mt.name(), outputStream);

            } catch (Exception ex) {
            	logger.error("Requested largeRaw image was not found" + ex);
                thumbnailHandler(documentId, scale, square, outputStream, response);
            }
        } else {
            logger.error("Requested image " + documentId + ".jpg was not found");
            MimeType mt = MimeType.JPEG;
            iu.load("/data/bie/images/noImage.jpg"); // problem with Jetty 7.0.1
            logger.debug("Loaded");                
            response.setContentType(mt.getMimeType());
            ImageIO.write(iu.getModifiedImage(), mt.name(), outputStream);
        }
    }
    
    /**
     * Pest / Conservation status list
     *
     * @param statusStr
     * @param filterQuery 
     * @param model
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/species/status/{status}", method = RequestMethod.GET)
    public String listStatus(
            @PathVariable("status") String statusStr,
            @RequestParam(value="fq", required=false) String filterQuery,
            Model model) throws Exception {
        StatusType statusType = StatusType.getForStatusType(statusStr);
        if (statusType==null) {
            return "redirect:/error.jsp";
        }
        model.addAttribute("statusType", statusType);
        model.addAttribute("filterQuery", filterQuery);
        SearchResultsDTO searchResults = searchDao.findAllByStatus(statusType, filterQuery,  0, 10, "score", "desc");// findByScientificName(query, startIndex, pageSize, sortField, sortDirection);
        model.addAttribute("searchResults", searchResults);
        return STATUS_LIST;
    }

    /**
     * Pest / Conservation status JSON (for yui datatable)
     *
     * @param statusStr
     * @param filterQuery 
     * @param startIndex
     * @param pageSize
     * @param sortField
     * @param sortDirection
     * @param model
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/species/status/{status}.json", method = RequestMethod.GET)
    public SearchResultsDTO listStatusJson(@PathVariable("status") String statusStr,
            @RequestParam(value="fq", required=false) String filterQuery,
            @RequestParam(value="startIndex", required=false, defaultValue="0") Integer startIndex,
            @RequestParam(value="results", required=false, defaultValue ="10") Integer pageSize,
            @RequestParam(value="sort", required=false, defaultValue="score") String sortField,
            @RequestParam(value="dir", required=false, defaultValue ="asc") String sortDirection,
            Model model) throws Exception {

        StatusType statusType = StatusType.getForStatusType(statusStr);
        SearchResultsDTO searchResults = null;
        sortDirection = SolrUtils.getSortDirection(sortField, sortDirection);        

        if (statusType!=null) {
            searchResults = searchDao.findAllByStatus(statusType, filterQuery, startIndex, pageSize, sortField, sortDirection);
            // findByScientificName(query, startIndex, pageSize, sortField, sortDirection);
        }

        return searchResults;
    }
    
    /**
     * JSON web service to return a list of scientific names for an input list of GUIDs
     * 
     * @param guids
     * @return names
     */
    @RequestMapping(value = "/species/namesFromGuids.json")
    public @ResponseBody List<String> getNamesForGuids(@RequestParam(value="guid", required=true) String[] guids) {
        List<String> names = new ArrayList<String>();        
        for (String guid : guids) {
            String name = null;
            try {
                TaxonConcept tc = taxonConceptDao.getByGuid(guid);
                if(tc != null)
                    name = tc.getNameString();
                else
                    logger.info("No TN found for guid " + guid);
            } catch (Exception ex) {
                logger.warn("No TN found for guid: " + guid, ex);
            }
            names.add(name); // note: will add null if no name is found as we want size of output list to be same as input array
        }
        return names;
    }

    /**
     * JSON web service to return a list of brief taxon profiles for an input list of GUIDs
     *
     * @return names
     */
    @RequestMapping(value = {"/species/fieldGuides", "/species/fieldGuides.json"}, method = RequestMethod.POST)
    public @ResponseBody List<TaxonDTO> getTaxonDTOForGuids(HttpServletRequest request) throws Exception {
        List<TaxonDTO> taxa = new ArrayList<TaxonDTO>();
        InputStream body = request.getInputStream();
        ObjectMapper om = new ObjectMapper();
        List<String> guids = om.readValue(body, new TypeReference<List<String>>(){});
        List<ExtendedTaxonConceptDTO> tcs = taxonConceptDao.getExtendedTaxonConceptByGuids(guids);
        for (ExtendedTaxonConceptDTO tc : tcs) {
            repoUrlUtils.fixRepoUrls(tc);
            TaxonDTO taxonDTO = createTaxonDTO(tc);
            if(taxonDTO !=null)
                taxa.add(taxonDTO);
        }
        return taxa;
    }
    
    
    /**
     * JSON web service to return a list of scientific names for an input list of GUIDs
     * 
     * @param guids
     * @return a map of guid's to a list of associated name (where the first name is the accepted concept name) 
     */
    @RequestMapping(value = "/species/bulklookup/namesFromGuids.json")
    public @ResponseBody Map<String,List<Map<String, String>>> getAllNamesForGuids(HttpServletRequest request, HttpServletResponse response) throws Exception {
        ObjectMapper om = new ObjectMapper();
        InputStream is = request.getInputStream();
        if(request.getContentLength()>0){
            String[] guids = om.readValue(is, (new String[0]).getClass());
            Map<String,List<Map<String, String>>> results = new HashMap<String,List<Map<String, String>>>();
            for(String guid:guids){
                results.put(guid, getNamesForGuids(guid));
            }
            return results;
        } else{
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unable to perform a bulklookup without a list of guids");
            return null;
        }
    }
    

    /**
     * JSON web service to return a list of synonyms for a GUID/LSID.
     * Note: accepted taxonConcept is included as first element of output list.
     * 
     * @param guid
     * @return synonyms
     */
    @RequestMapping(value = "/species/synonymsForGuid/{guid:.+}*", method = RequestMethod.GET)
    public @ResponseBody  List<Map<String, String>> getNamesForGuids(@PathVariable("guid") String guid) {
        List<Map<String, String>> synonyms = new ArrayList<Map<String, String>>();
        
        try {
            List tcs = taxonConceptDao.getSynonymsFor(guid);
            TaxonConcept accepted = taxonConceptDao.getByGuid(guid);
            tcs.add(0, accepted);
            
            for (Object obj : tcs) {
                TaxonConcept tc = (TaxonConcept)obj;
                Map<String, String> syn = new HashMap<String, String>();
                String name = tc.getNameString();
                String author = (tc.getAuthor() != null) ? tc.getAuthor() : "";
                String nameComplete = name;
                // inconsistent format in nameString - some include author and others don't...
                if (!author.isEmpty() && StringUtils.contains(name, author)) {
                    nameComplete = name;
                    name = StringUtils.remove(name, author).trim();
                } else if (!author.isEmpty()) {
                    nameComplete = name + " " + author;
                } 
                
                //syn.put(tc.getGuid(), nameAuthor);
                syn.put("guid", tc.getGuid());
                syn.put("nameComplete", nameComplete);
                syn.put("name", name);
                syn.put("author", author);
                synonyms.add(syn);
            }
            
        } catch (Exception ex) {
            logger.warn("No TNs found for guid: " + guid, ex);
        }
        
        return synonyms;
    }

    /**
     * Filter a list of SimpleProperty objects so that the resulting list only
     * contains objects with a name ending in "Text". E.g. "hasDescriptionText".
     *
     * @param etc
     * @return
     */
    private List<SimpleProperty> filterSimpleProperties(ExtendedTaxonConceptDTO etc) {
        List<SimpleProperty> simpleProperties = etc.getSimpleProperties();
        List<SimpleProperty> textProperties = new ArrayList<SimpleProperty>();

        //we only want the list to store the first type for each source
        //HashSet<String> processedProperties = new HashSet<String>();
        Hashtable<String, SimpleProperty> processProperties = new Hashtable<String, SimpleProperty>();
        for (SimpleProperty sp : simpleProperties) {
            String thisProperty = sp.getName() + sp.getInfoSourceName();
            if ((sp.getName().endsWith("Text") || sp.getName().endsWith("hasPopulateEstimate"))) {
                //attempt to find an existing processed property
                SimpleProperty existing = processProperties.get(thisProperty);
                if (existing != null) {
                    //separate paragraphs using br's instead of p so that the citation is aligned correctly
                    existing.setValue(existing.getValue() + "<br><br>" + sp.getValue());
                } else {
                    processProperties.put(thisProperty, sp);
                }
            }
        }
        simpleProperties = Collections.list(processProperties.elements());
        //sort the simple properties based on low priority and non-truncated infosources
        Collections.sort(simpleProperties, new SimplePropertyComparator());
        for (SimpleProperty sp : simpleProperties) {
            if (!nonTruncatedSources.contains(sp.getInfoSourceURL())) {
                sp.setValue(truncateTextBySentence(sp.getValue(), 300));
            }
            textProperties.add(sp);
        }

        return textProperties;
    }

    /**
     * Truncates the text at a sentence break after min length
     * @param text
     * @param min
     * @return
     */
    private String truncateTextBySentence(String text, int min) {
        try {
            if (text != null && text.length() > min) {
                java.text.BreakIterator bi = java.text.BreakIterator.getSentenceInstance();
                bi.setText(text);
                int finalIndex = bi.following(min);
                return text.substring(0, finalIndex) + "...";
            }
        } catch (Exception e) {
            logger.debug("Unable to truncate " + text, e);
        }
        return text;
    }

    /**
     * Truncates the text at a word break after min length
     *
     * @param text
     * @param min
     * @return
     */
    private String truncateTextByWord(String text, int min) {
        try {
            if (text != null && text.length() > min) {
                BreakIterator bi = BreakIterator.getWordInstance();
                bi.setText(text);
                int finalIndex = bi.following(min);
                String truncatedText = text.substring(0, finalIndex) + "...";
                logger.debug("truncate at position: " + finalIndex + " | min: " + min);
                logger.debug("truncated text = " + truncatedText);
                return truncatedText;
            }
        } catch (Exception e) {
            logger.warn("Unable to truncate " + text, e);
        }
        return text;
    }

    /**
     * Return a JSON object (map) for the infosources for a given TC guid
     *
     * @param guid
     * @param response
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/ws/infosources/{guid:.+}*", method = RequestMethod.GET)
    public @ResponseBody Map<String, InfoSourceDTO> imageSearch(@PathVariable("guid") String guid, HttpServletResponse response) throws Exception {
        ExtendedTaxonConceptDTO etc = taxonConceptDao.getExtendedTaxonConceptByGuid(guid);

        if (etc != null || !etc.getSimpleProperties().isEmpty()) {
            logger.debug("ExtendedTaxonConceptDTO = " + etc.getTaxonConcept());
            return getInfoSource(etc);
        } else {
            logger.warn("GUID not found: " + guid);
            response.sendError(response.SC_NOT_FOUND, "Requested taxon concept guid " + guid + " was not found");
            return null;
        }
    }

    /**
     * Create a list of unique infoSources to display on Overview page.
     * 
     * @param etc
     * @return
     */
    private Map<String, InfoSourceDTO> getInfoSource(ExtendedTaxonConceptDTO etc) {
        Map<String, InfoSourceDTO> infoSourceMap = new TreeMap<String, InfoSourceDTO>();

        // Look in each property of the ExtendedTaxonConceptDTO
        if (etc.getTaxonConcept() != null) {
            String text = "Name: " + etc.getTaxonConcept().getNameString() + " " + etc.getTaxonConcept().getAuthor();
            extractInfoSources(etc.getTaxonConcept(), infoSourceMap, text, "Names");
        }
        if (etc.getTaxonName() != null) {
            extractInfoSources(etc.getTaxonName(), infoSourceMap, "Name: " + etc.getTaxonName().getNameComplete(), "Names");
        }
        if (etc.getCommonNames() != null) {
            for (CommonName cn : etc.getCommonNames()) {
                extractInfoSources(cn, infoSourceMap, "Common Name: " + cn.getNameString(), "Names");
            }
        }
        if (etc.getSimpleProperties() != null) {
            for (SimpleProperty sp : etc.getSimpleProperties()) {
                String label = StringUtils.substringAfter(sp.getName(), "#");
                extractInfoSources(sp, infoSourceMap, sp.getValue(), label);
            }
        }
        if (etc.getPestStatuses() != null) {
            for (PestStatus ps : etc.getPestStatuses()) {
                extractInfoSources(ps, infoSourceMap, "Status: " + ps.getStatus(), "Status");
            }
        }
        if(etc.getCategories() != null){
            for  (Category cat : etc.getCategories())  {
                extractInfoSources(cat, infoSourceMap, "Category: " + cat.getCategory(), "Category");
            }
        }
        if (etc.getConservationStatuses() != null) {
            for (ConservationStatus cs : etc.getConservationStatuses()) {
                extractInfoSources(cs, infoSourceMap, "Status: " + cs.getStatus(), "Status");
            }
        }
        if (etc.getExtantStatuses() != null) {
            for (ExtantStatus es : etc.getExtantStatuses()) {
                extractInfoSources(es, infoSourceMap, "Status: " + es.getStatusAsString(), "Status");
            }
        }
        if (etc.getHabitats() != null) {
            for (Habitat hb : etc.getHabitats()) {
                extractInfoSources(hb, infoSourceMap, "Status: " + hb.getStatusAsString(), "Status");
            }
        }
        if (etc.getImages() != null) {
            for (Image img : etc.getImages()) {
                StringBuilder text = new StringBuilder("Image from " + img.getInfoSourceName());
                if (img.getCreator() != null) {
                    text.append("(by ").append(img.getCreator()).append(")");
                }
                /*
                // issue 346: removed below section
                if (img.getIsPartOf() != null) {
                    extractInfoSources(img, infoSourceMap, text.toString(), "Images", img.getIsPartOf());
                } else {
                    extractInfoSources(img, infoSourceMap, text.toString(), "Images");
                }
                */
                extractInfoSources(img, infoSourceMap, text.toString(), "Images");
            }
        }
        if (etc.getDistributionImages() != null) {
            for (Image img : etc.getDistributionImages()) {
                StringBuilder text = new StringBuilder("Distribution image from " + img.getInfoSourceName());
                if (img.getCreator() != null) {
                    text.append("(by ").append(img.getCreator()).append(")");
                }
                /*
                // issue 346: removed below section
                if (img.getIsPartOf() != null) {
                    extractInfoSources(img, infoSourceMap, text.toString(), "Images", img.getIsPartOf());
                } else {
                    extractInfoSources(img, infoSourceMap, text.toString(), "Images");
                }
                */
                extractInfoSources(img, infoSourceMap, text.toString(), "Images");
            }
        }
        if (etc.getScreenshotImages() != null) {
            for (Image img : etc.getScreenshotImages()) {
                StringBuilder text = new StringBuilder("Screenshot image from " + img.getInfoSourceName());

                //System.out.println(img.getRepoLocation());
                if (img.getCreator() != null) {
                    text.append("(by ").append(img.getCreator()).append(")");
                }
                /*
                // issue 346: removed below section
                if (img.getIsPartOf() != null) {
                    extractInfoSources(img, infoSourceMap, text.toString(), "Images", img.getIsPartOf());
                } else {
                    extractInfoSources(img, infoSourceMap, text.toString(), "Images");
                }
                */
                extractInfoSources(img, infoSourceMap, text.toString(), "Images");
            }
        }        
        if (etc.getPublicationReference() != null) {
            for (Reference ref : etc.getPublicationReference()) {
                extractInfoSources(ref, infoSourceMap, "Reference: " + ref.getTitle(), "Publication");
            }
        }

        return infoSourceMap;
    }

    /**
     * Extract Info Source information from various AttributableObject's to create a Map of
     * Info Sources for display on web page.
     *
     * @param ao
     * @param infoSourceMap
     * @param text
     * @param section
     */
    private void extractInfoSources(AttributableObject ao, Map<String, InfoSourceDTO> infoSourceMap, String text, String section) {
        extractInfoSources(ao, infoSourceMap, text, section, ao.getIdentifier()); // ao.getInfoSourceURL()
    }

    /**
     * Extract Info Source information from various AttributableObject's to create a Map of
     * Info Sources for display on web page. Takes custom identifier text property.
     *
     * @param ao
     * @param infoSourceMap
     * @param text
     * @param section
     * @param identifier
     */
    private void extractInfoSources(AttributableObject ao, Map<String, InfoSourceDTO> infoSourceMap, String text, String section, String identifier) {
        if (ao != null && ao.getInfoSourceName() != null) {
            String infoSourceName = ao.getInfoSourceName();

            if (infoSourceMap.containsKey(infoSourceName)) {
                // key exists so add to existing object
                InfoSourceDTO is = infoSourceMap.get(infoSourceName);
                if (is.getText() == null || is.getText().isEmpty()) {
                    is.setText(truncateTextByWord(text, TEXT_MAX_WIDTH)); // truncate text
                }
                if (!section.isEmpty()) {
                    Set<String> sections = null;
                    if (is.getSections() != null) {
                        sections = new LinkedHashSet<String>(is.getSections());
                    } else {
                        sections = new LinkedHashSet<String>();
                    }
                    sections.add(section);
                    is.setSections(sections);
                }
            } else {
                // no key so create one
                InfoSourceDTO is = new InfoSourceDTO();
                is.setInfoSourceName(infoSourceName);
                //String identifier = ao.getIdentifier();
                if (identifier != null && !identifier.isEmpty()) {
                    is.setIdentifier(identifier);
                }
                is.setInfoSourceURL(ao.getInfoSourceURL());
                is.setInfoSourceId(ao.getInfoSourceId());
                is.setText(truncateTextByWord(text, TEXT_MAX_WIDTH)); // truncate text
                if (!section.isEmpty()) {
                    Set<String> sections = new LinkedHashSet<String>();
                    sections.add(section);
                    is.setSections(sections);
                }
                infoSourceMap.put(infoSourceName, is);
            }
        }
    }

    /**
     * Comparator to order the Simple Properties based on their natural ordering
     * and low and high priority info sources.
     */
    protected class SimplePropertyComparator implements Comparator<SimpleProperty>{

        @Override
        public int compare(SimpleProperty o1, SimpleProperty o2) {
            int value = o1.compareTo(o2);

            if(value ==0){
                //we want the low priority items to appear at the end of the list
                boolean low1 = lowPrioritySources.contains(o1.getInfoSourceURL());
                boolean low2 = lowPrioritySources.contains(o2.getInfoSourceURL());
                if(low1 &&low2) return 0;
                if(low1) return 1;
                if(low2) return -1;
                //we want the non-truncated infosources to appear at the top of the list
                boolean hi1 = nonTruncatedSources.contains(o1.getInfoSourceURL());
                boolean hi2 = nonTruncatedSources.contains(o2.getInfoSourceURL());
                if(hi1&&hi2) return 0;
                if(hi1) return -1;
                if(hi2) return 1;
            }
            return value;
        }
    }

    @RequestMapping(value="/species/isAustralian.json*", method = RequestMethod.GET)
    public void getJson(           
            @RequestParam(value="guid", defaultValue ="", required=true) String guid, 
            @RequestParam(value="isAussie", defaultValue ="", required=true) String isAussie,
            HttpServletResponse response) throws Exception {
    	String jsonString = "{}";
    	
    	if(guid != null && guid.length() > 0){    		    	
	    	jsonString = PageUtils.getUrlContentAsJsonString("http://biocache.ala.org.au/ws/australian/taxon/" + guid);
	 
	    	ObjectMapper om = new ObjectMapper();
	        Map map = om.readValue(jsonString, Map.class);
	        if(map != null && isAussie != null){
	        	Object isAustralian = map.get("isAustralian");
	        	if(!isAussie.trim().equalsIgnoreCase(isAustralian.toString())){
	        		try{
	        		    //reindex the taxon concept too.
	        			taxonConceptDao.setIsAustralian(guid, ((Boolean)isAustralian).booleanValue(), true);	        			        			
	        		}
	        		catch(Exception ex){
	        			logger.error(ex);
	        		}
	        	}
	        }
    	}
    	response.setContentType("application/json;charset=UTF-8");
    	response.setStatus(200);
    	PrintWriter out = response.getWriter();    	
    	out.write(jsonString);
    	out.flush();
    	out.close();
    	response.flushBuffer();
    }


    public void setNonTruncatedSources(Set<String> nonTruncatedSources) {
        this.nonTruncatedSources = nonTruncatedSources;
    }

    /**
     * @param infoSourceDAO the infoSourceDAO to set
     */
    public void setInfoSourceDAO(InfoSourceDAO infoSourceDAO) {
        this.infoSourceDAO = infoSourceDAO;
    }

    /**
     * @param taxonConceptDao the taxonConceptDao to set
     */
    public void setTaxonConceptDao(TaxonConceptDao taxonConceptDao) {
        this.taxonConceptDao = taxonConceptDao;
    }

    /**
     * @param repoUrlUtils the repoUrlUtils to set
     */
    public void setRepoUrlUtils(RepoUrlUtils repoUrlUtils) {
        this.repoUrlUtils = repoUrlUtils;
    }

    public Set<String> getNonTruncatedSources() {
        return nonTruncatedSources;
    }

    public Set<String> getLowPrioritySources() {
        return lowPrioritySources;
    }

    public void setLowPrioritySources(Set<String> lowPrioritySources) {
        this.lowPrioritySources = lowPrioritySources;
    }
}

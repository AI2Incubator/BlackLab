package nl.inl.blacklab.server.requesthandlers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import javax.servlet.http.HttpServletRequest;

import nl.inl.blacklab.search.*;
import nl.inl.blacklab.search.indexmetadata.Annotation;
import nl.inl.blacklab.search.indexmetadata.MetadataField;
import nl.inl.blacklab.search.results.*;
import nl.inl.blacklab.server.jobs.ContextSettings;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BooleanQuery.Builder;
import org.apache.lucene.search.Query;

import nl.inl.blacklab.resultproperty.DocProperty;
import nl.inl.blacklab.resultproperty.PropertyValue;
import nl.inl.blacklab.server.BlackLabServer;
import nl.inl.blacklab.server.config.DefaultMax;
import nl.inl.blacklab.server.datastream.DataStream;
import nl.inl.blacklab.server.exceptions.BlsException;
import nl.inl.blacklab.server.jobs.User;
import nl.inl.blacklab.server.jobs.WindowSettings;
import nl.inl.blacklab.server.search.BlsCacheEntry;
import org.eclipse.collections.api.set.primitive.MutableIntSet;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;

/**
 * Request handler for grouped hit results.
 */
public class RequestHandlerHitsGrouped extends RequestHandler {

    public static final boolean INCLUDE_RELATIVE_FREQ = true;

    public RequestHandlerHitsGrouped(BlackLabServer servlet, HttpServletRequest request, User user, String indexName,
            String urlResource, String urlPathPart) {
        super(servlet, request, user, indexName, urlResource, urlPathPart);
    }

    @Override
    public int handle(DataStream ds) throws BlsException {
        // Get the window we're interested in
        BlsCacheEntry<HitGroups> search = searchMan.searchNonBlocking(user, searchParam.hitsGrouped());

        // Search is done; construct the results object
        HitGroups groups;
        try {
            groups = search.get();
        } catch (InterruptedException | ExecutionException e) {
            throw RequestHandler.translateSearchException(e);
        }

        ds.startMap();
        ds.startEntry("summary").startMap();
        WindowSettings windowSettings = searchParam.getWindowSettings();
        final int first = windowSettings.first() < 0 ? 0 : windowSettings.first();
        DefaultMax pageSize = searchMan.config().getParameters().getPageSize();
        final int requestedWindowSize = windowSettings.size() < 0
                || windowSettings.size() > pageSize.getMax() ? pageSize.getDefaultValue()
                        : windowSettings.size();
        int totalResults = groups.size();
        final int actualWindowSize = first + requestedWindowSize > totalResults ? totalResults - first
                : requestedWindowSize;
        WindowStats ourWindow = new WindowStats(first + requestedWindowSize < totalResults, first, requestedWindowSize, actualWindowSize);
        addSummaryCommonFields(ds, searchParam, search.timeUserWaited(), 0, groups, ourWindow);
        ResultCount hitsStats = searchMan.search(user, searchParam.hitsCount());
        ResultCount docsStats = searchMan.search(user, searchParam.docsCount());

        // The list of groups found
        DocProperty metadataGroupProperties = null;
        DocResults subcorpus = null;
        CorpusSize subcorpusSize = null;
        if (INCLUDE_RELATIVE_FREQ) {
            metadataGroupProperties = groups.groupCriteria().docPropsOnly();
            subcorpus = searchMan.search(user, searchParam.subcorpus());
            subcorpusSize = subcorpus.subcorpusSize();
        }

        addNumberOfResultsSummaryTotalHits(ds, hitsStats, docsStats, false, subcorpusSize);
        ds.endMap().endEntry();

        searchLogger.setResultsFound(groups.size());

        Map<Integer, String> pids = new HashMap<>();

        int i = 0;
        ds.startEntry("hitGroups").startList();
        for (HitGroup group : groups) {
            if (i >= first && i < first + requestedWindowSize) {
                logger.debug("## Group number " + i);

                if (INCLUDE_RELATIVE_FREQ && metadataGroupProperties != null) {
                    // Find size of corresponding subcorpus group
                    PropertyValue docPropValues = groups.groupCriteria().docPropValues(group.identity());
                    //DocGroup groupSubcorpus = subcorpusGrouped.get(docPropValues);
                    //tokensInSubcorpus = groupSubcorpus.storedResults().tokensInMatchingDocs();
                    subcorpusSize = findSubcorpusSize(searchParam, subcorpus.query(), metadataGroupProperties, docPropValues, true);
                    logger.debug("## tokens in subcorpus group: " + subcorpusSize.getTokens());
                }

                int numberOfDocsInGroup = group.storedResults().docsStats().countedTotal();

                ds.startItem("hitgroup").startMap();
                ds.entry("identity", group.identity().serialize())
                        .entry("identityDisplay", group.identity().toString())
                        .entry("size", group.size());
                if (INCLUDE_RELATIVE_FREQ) {
                    ds.entry("numberOfDocs", numberOfDocsInGroup);
                    if (metadataGroupProperties != null) {
                        addSubcorpusSize(ds, subcorpusSize);
                    }
                }

                Hits hitsInGroup = group.storedResults();
                writeHits(ds, hitsInGroup, pids);

                ds.endMap().endItem();
            }
            i++;
        }
        ds.endList().endEntry();
        writeDocInfos(ds, groups, pids, first, requestedWindowSize);
        ds.endMap();

        return HTTP_OK;
    }

    private void writeHits(DataStream ds, Hits hits, Map<Integer, String> pids) throws BlsException {
        ds.startEntry("hits").startList();
        BlackLabIndex index = hits.index();
        ContextSettings contextSettings = searchParam.getContextSettings();
        Concordances concordances = null;
        Kwics kwics = null;
        if (contextSettings.concType() == ConcordanceType.CONTENT_STORE)
            concordances = hits.concordances(contextSettings.size(), ConcordanceType.CONTENT_STORE);
        else
            kwics = hits.kwics(contextSettings.size());

        for (Hit hit : hits) {
            ds.startItem("hit").startMap();

            // Find pid
            String pid = pids.get(hit.doc());
            if (pid == null) {
                Document document = index.doc(hit.doc()).luceneDoc();
                pid = getDocumentPid(index, hit.doc(), document);
                pids.put(hit.doc(), pid);
            }

            // TODO: use RequestHandlerDocSnippet.getHitOrFragmentInfo()

            // Add basic hit info
            ds.entry("docPid", pid);
            ds.entry("start", hit.start());
            ds.entry("end", hit.end());

            if (hits.hasCapturedGroups()) {
                Map<String, Span> capturedGroups = hits.capturedGroups().getMap(hit);
                ds.startEntry("captureGroups").startList();

                for (Map.Entry<String, Span> capturedGroup : capturedGroups.entrySet()) {
                    if (capturedGroup.getValue() != null) {
                        ds.startItem("group").startMap();
                        ds.entry("name", capturedGroup.getKey());
                        ds.entry("start", capturedGroup.getValue().start());
                        ds.entry("end", capturedGroup.getValue().end());
                        ds.endMap().endItem();
                    }
                }

                ds.endList().endEntry();
            }

            if (contextSettings.concType() == ConcordanceType.CONTENT_STORE) {
                // Add concordance from original XML
                Concordance c = concordances.get(hit);
                ds.startEntry("left").plain(c.left()).endEntry()
                        .startEntry("match").plain(c.match()).endEntry()
                        .startEntry("right").plain(c.right()).endEntry();
            } else {
                // Add KWIC info
                Kwic c = kwics.get(hit);
                Set<Annotation> annotationsToList = new HashSet<>(getAnnotationsToWrite());
                ds.startEntry("left").contextList(c.annotations(), annotationsToList, c.left()).endEntry()
                        .startEntry("match").contextList(c.annotations(), annotationsToList, c.match()).endEntry()
                        .startEntry("right").contextList(c.annotations(), annotationsToList, c.right()).endEntry();
            }
            ds.endMap().endItem();
        }
        ds.endList().endEntry();
    }

    private void writeDocInfos(DataStream ds, HitGroups hitGroups, Map<Integer, String> pids, int first, int requestedWindowSize) throws BlsException {
        ds.startEntry("docInfos").startMap();
        //DataObjectMapAttribute docInfos = new DataObjectMapAttribute("docInfo", "pid");
        BlackLabIndex index = hitGroups.index();
        MutableIntSet docsDone = new IntHashSet();
        Document doc = null;
        String lastPid = "";
        Set<MetadataField> metadataFieldsTolist = new HashSet<>(this.getMetadataToWrite());

        int i = 0;
        for (HitGroup group : hitGroups) {
            if (i >= first && i < first + requestedWindowSize) {
                for (Hit hit : group.storedResults()) {
                    String pid = pids.get(hit.doc());

                    // Add document info if we didn't already
                    if (!docsDone.contains(hit.doc())) {
                        docsDone.add(hit.doc());
                        ds.startAttrEntry("docInfo", "pid", pid);
                        if (!pid.equals(lastPid)) {
                            doc = index.doc(hit.doc()).luceneDoc();
                            lastPid = pid;
                        }
                        dataStreamDocumentInfo(ds, index, doc, metadataFieldsTolist);
                        ds.endAttrEntry();
                    }
                }
            }
            i++;
        }

        ds.endMap().endEntry();
    }

    static CorpusSize findSubcorpusSize(SearchParameters searchParam, Query metadataFilterQuery, DocProperty property, PropertyValue value, boolean countTokens) {
        if (!property.canConstructQuery(searchParam.blIndex(), value))
            return CorpusSize.EMPTY; // cannot determine subcorpus size of empty value
        // Construct a query that matches this propery value
        Query query = property.query(searchParam.blIndex(), value); // analyzer....!
        if (query == null) {
            query = metadataFilterQuery;
        } else {
            // Combine with subcorpus query
            Builder builder = new BooleanQuery.Builder();
            builder.add(metadataFilterQuery, Occur.MUST);
            builder.add(query, Occur.MUST);
            query = builder.build();
        }
        // Determine number of tokens in this subcorpus
        return searchParam.blIndex().queryDocuments(query).subcorpusSize(countTokens);
    }

}

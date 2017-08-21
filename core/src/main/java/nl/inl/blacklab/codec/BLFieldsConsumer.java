package nl.inl.blacklab.codec;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.codecs.FieldsConsumer;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.BytesRef;

/**
 * BlackLab FieldsConsumer: writes postings information to the index,
 * using a delegate and extending its functionality by also writing a forward
 * index.
 *
 * Adapted from <a href="https://github.com/meertensinstituut/mtas/">MTAS</a>.
 */
public class BLFieldsConsumer extends FieldsConsumer {

    protected static final Logger logger = LogManager.getLogger(BLFieldsConsumer.class);

    /** The delegate whose functionality we're extending */
    private FieldsConsumer delegateFieldsConsumer;

    /** Current segment write state, used when writing to the index */
    private SegmentWriteState state;

    /** The posting format's name */
    @SuppressWarnings("unused")
    private String postingFormatName;

    /** Name of the posting format we've extended */
    private String delegatePostingsFormatName;

    private String testFileName;

    public BLFieldsConsumer(FieldsConsumer fieldsConsumer,
            SegmentWriteState state, String name, String delegatePostingsFormatName) {
        this.delegateFieldsConsumer = fieldsConsumer;
        this.state = state;
        this.postingFormatName = name;
        this.delegatePostingsFormatName = delegatePostingsFormatName;

        testFileName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, "bltest");
    }

//    /*
//     * (non-Javadoc)
//     *
//     * @see org.apache.lucene.codecs.FieldsConsumer#merge(org.apache.lucene.index.
//     * MergeState)
//     */
//    @Override
//    public void merge(MergeState mergeState) throws IOException {
//        final List<Fields> fields = new ArrayList<>();
//        final List<ReaderSlice> slices = new ArrayList<>();
//
//        int docBase = 0;
//
//        for (int readerIndex = 0; readerIndex < mergeState.fieldsProducers.length; readerIndex++) {
//            final FieldsProducer f = mergeState.fieldsProducers[readerIndex];
//
//            final int maxDoc = mergeState.maxDocs[readerIndex];
//            f.checkIntegrity();
//            slices.add(new ReaderSlice(docBase, maxDoc, readerIndex));
//            fields.add(f);
//            docBase += maxDoc;
//        }
//
//        Fields mergedFields = new MappedMultiFields(mergeState,
//                new MultiFields(fields.toArray(Fields.EMPTY_ARRAY),
//                        slices.toArray(ReaderSlice.EMPTY_ARRAY)));
//        write(mergedFields);
//    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.lucene.codecs.FieldsConsumer#write(org.apache.lucene.index.
     * Fields )
     */
    @Override
    public void write(Fields fields) throws IOException {
        delegateFieldsConsumer.write(fields);
        //write(state.fieldInfos, fields);

        FieldInfos fieldInfos = state.fieldInfos;
        try (IndexOutput outObject = state.directory.createOutput(testFileName, state.context)) {
            outObject.writeString(delegatePostingsFormatName);

            // For each field...
            for (String field: fields) {
                Terms terms = fields.terms(field);
                if (terms == null) {
                    continue;
                }
                boolean hasPositions = terms.hasPositions();
                boolean hasFreqs = terms.hasFreqs();
                boolean hasOffsets = terms.hasOffsets();
//                FieldInfo fieldInfo = fieldInfos.fieldInfo(field);
//                boolean hasPayloads = fieldInfo.hasPayloads();
                // If it's (part of) a complex field...
                if (hasFreqs && hasPositions) {

                    // Determine postings flags
                    int flags = PostingsEnum.POSITIONS;  // | PostingsEnum.PAYLOADS;
                    if (hasOffsets)
                        flags = flags | PostingsEnum.OFFSETS;
                    PostingsEnum postingsEnum = null; // we'll reuse this later for efficiency

                    // For each term in this field...
                    TermsEnum termsEnum = terms.iterator();
                    while (true) {
                        BytesRef term = termsEnum.next();
                        if (term == null)
                            break;

                        // store term and get ref
//                      Long termRef = outTerm.getFilePointer();
//                      outTerm.writeString(term.utf8ToString());

                        // For each document containing this term...
                        postingsEnum = termsEnum.postings(postingsEnum, flags);
                        while (true) {
                            Integer docId = postingsEnum.nextDoc();
                            if (docId.equals(DocIdSetIterator.NO_MORE_DOCS))
                                break;

                            // For each occurrence of term in this doc...
                            for (int i = 0; i < postingsEnum.freq(); i++) {
                                int position = postingsEnum.nextPosition();
                                //BytesRef payload = postingsEnum.getPayload();

                            } // end loop positions
                        } // end loop docs
                    }
                    // Store additional metadata about this field
                    fieldInfos.fieldInfo(field).putAttribute("funFactsAboutField", "didYouKnowThat?");
                }
            }
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.lucene.codecs.FieldsConsumer#close()
     */
    @Override
    public void close() throws IOException {
        delegateFieldsConsumer.close();
    }

}
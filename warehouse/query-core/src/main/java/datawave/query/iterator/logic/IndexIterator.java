package datawave.query.iterator.logic;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Lists;
import datawave.query.Constants;
import datawave.query.attributes.Document;
import datawave.query.attributes.PreNormalizedAttributeFactory;
import datawave.query.composite.CompositeUtils;
import datawave.query.iterator.DocumentIterator;
import datawave.query.iterator.Util;
import datawave.query.iterator.filter.composite.CompositePredicateFilter;
import datawave.query.iterator.filter.composite.CompositePredicateFilterer;
import datawave.query.jexl.functions.FieldIndexAggregator;
import datawave.query.jexl.functions.IdentityAggregator;
import datawave.query.predicate.SeekingFilter;
import datawave.query.predicate.TimeFilter;
import datawave.query.util.TypeMetadata;
import org.apache.accumulo.core.data.ArrayByteSequence;
import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.PartialKey;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.IteratorEnvironment;
import org.apache.accumulo.core.iterators.SortedKeyValueIterator;
import org.apache.commons.jexl2.parser.JexlNode;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Scans a bounds within a column qualifier. This iterator needs to: - 1) Be given a global Range (ie, [-inf,+inf]) - 2) Select an arbitrary column family (ie,
 * "fi\u0000FIELD") - 3) Given a prefix, scan all keys that have a column qualifer that has that prefix that occur in the column family for all rows in a tablet
 * 
 */
public class IndexIterator implements SortedKeyValueIterator<Key,Value>, DocumentIterator, CompositePredicateFilterer {
    private static final Logger log = Logger.getLogger(IndexIterator.class);
    
    public static class Builder<T extends IndexIterator,B extends Builder<T,B>> {
        protected Text field;
        protected Text value;
        protected SortedKeyValueIterator<Key,Value> source;
        protected TimeFilter timeFilter = TimeFilter.alwaysTrue();
        protected boolean buildDocument = false;
        protected TypeMetadata typeMetadata;
        protected Predicate<Key> datatypeFilter = Predicates.alwaysTrue();
        protected FieldIndexAggregator aggregation;
        protected Map<String,Map<String,CompositePredicateFilter>> compositePredicateFilters;
        
        protected Builder(Text field, Text value, SortedKeyValueIterator<Key,Value> source) {
            this.field = field;
            this.value = value;
            this.source = source;
        }
        
        public B withTimeFilter(TimeFilter timeFilter) {
            this.timeFilter = timeFilter;
            return (B) this;
        }
        
        public B shouldBuildDocument(boolean buildDocument) {
            this.buildDocument = buildDocument;
            return (B) this;
        }
        
        public B withTypeMetadata(TypeMetadata typeMetadata) {
            this.typeMetadata = typeMetadata;
            return (B) this;
        }
        
        public B withDatatypeFilter(Predicate<Key> datatypeFilter) {
            this.datatypeFilter = datatypeFilter;
            return (B) this;
        }
        
        public B withAggregation(FieldIndexAggregator aggregation) {
            this.aggregation = aggregation;
            return (B) this;
        }
        
        public B withCompositePredicateFilters(Map<String,Map<String,CompositePredicateFilter>> compositePredicateFilters) {
            this.compositePredicateFilters = compositePredicateFilters;
            return (B) this;
        }
        
        public T build() {
            return (T) new IndexIterator(this);
        }
    }
    
    public static Builder builder(Text field, Text value, SortedKeyValueIterator<Key,Value> source) {
        return new Builder(field, value, source);
    }
    
    public static final String INDEX_FILTERING_CLASSES = "indexfiltering.classes";
    
    protected SortedKeyValueIterator<Key,Value> source;
    protected final Text valueMinPrefix;
    protected final Text columnFamily;
    protected final Collection<ByteSequence> seekColumnFamilies;
    protected final boolean includeColumnFamilies;
    
    // used for managing parent calls to seek
    protected Range scanRange;
    
    protected Key tk;
    protected Value tv;
    
    protected final String field;
    protected final String value;
    
    protected PreNormalizedAttributeFactory attributeFactory;
    protected Document document;
    protected boolean buildDocument = false;
    protected Predicate<Key> datatypeFilter;
    protected SeekingFilter dataTypeSeekingFilter;
    protected final FieldIndexAggregator aggregation;
    protected TimeFilter timeFilter;
    protected SeekingFilter timeSeekingFilter;
    private Map<String,Map<String,CompositePredicateFilter>> compositePredicateFilters;
    
    protected IndexIterator(Builder builder) {
        this(builder.field, builder.value, builder.source, builder.timeFilter, builder.typeMetadata, builder.buildDocument, builder.datatypeFilter,
                        builder.aggregation, builder.compositePredicateFilters);
    }
    
    IndexIterator(Text field, Text value, SortedKeyValueIterator<Key,Value> source, TimeFilter timeFilter, TypeMetadata typeMetadata, boolean buildDocument,
                    Predicate<Key> datatypeFilter, FieldIndexAggregator aggregator, Map<String,Map<String,CompositePredicateFilter>> compositePredicateFilters) {
        
        valueMinPrefix = Util.minPrefix(value);
        
        this.datatypeFilter = datatypeFilter;
        if (datatypeFilter instanceof SeekingFilter) {
            dataTypeSeekingFilter = (SeekingFilter) datatypeFilter;
        }
        
        this.source = source;
        this.timeFilter = timeFilter;
        if (timeFilter instanceof SeekingFilter) {
            timeSeekingFilter = (SeekingFilter) timeFilter;
        }
        
        // Build the cf: fi\x00FIELD_NAME
        this.columnFamily = new Text(Constants.FI_PREFIX);
        this.columnFamily.append(Constants.TEXT_NULL.getBytes(), 0, Constants.TEXT_NULL.getLength());
        this.columnFamily.append(field.getBytes(), 0, field.getLength());
        
        // Copy the byte[] by hand because ArrayByteSequence doesn't
        // The underlying bytes could be modified even though columnFamily is final
        byte[] columnFamilyBytes = new byte[this.columnFamily.getLength()];
        System.arraycopy(this.columnFamily.getBytes(), 0, columnFamilyBytes, 0, this.columnFamily.getLength());
        
        // Make sure we properly set the ColumnFamilies when calling seek() to avoid
        // opening readers to locality groups we don't care about
        this.seekColumnFamilies = Lists.newArrayList((ByteSequence) new ArrayByteSequence(columnFamilyBytes));
        this.includeColumnFamilies = true;
        
        document = new Document();
        
        this.field = field.toString();
        this.value = value.toString();
        
        // Only when this source is running over an indexOnlyField
        // do we want to add it to the Document
        this.buildDocument = buildDocument;
        if (this.buildDocument) {
            if (typeMetadata == null) {
                typeMetadata = new TypeMetadata();
            }
            
            // Values coming from the field index are already normalized.
            // Create specialized attributes to encapsulate this and avoid
            // double normalization
            attributeFactory = new PreNormalizedAttributeFactory(typeMetadata);
        }
        
        this.aggregation = aggregator;
        
        this.timeFilter = timeFilter;
        this.compositePredicateFilters = compositePredicateFilters;
    }
    
    @Override
    public boolean hasTop() {
        return tk != null;
    }
    
    @Override
    public void next() throws IOException {
        // We need to null this every time even though our fieldname and fieldvalue won't
        // change, we have the potential for the column visibility to change
        document = new Document();
        
        tk = null;
        // reusable buffers
        Text row = new Text(), cf = new Text(), cq = new Text();
        while (source.hasTop() && tk == null) {
            Key top = source.getTopKey();
            
            row = top.getRow(row);
            
            // Compare the current topKey's columnFamily against what we expect to receive
            cf = top.getColumnFamily(cf);
            int cfDiff = columnFamily.compareTo(cf);
            
            // check value, type, uid (bar\x00type\x00uid)
            cq = top.getColumnQualifier(cq);
            int cqDiff = Util.prefixDiff(valueMinPrefix, cq);
            
            if (cfDiff > 0) {
                // need try and find our columnFamily
                Key newStart = new Key(row, columnFamily, valueMinPrefix);
                Range newRange = new Range(newStart, false, scanRange.getEndKey(), scanRange.isEndKeyInclusive());
                
                if (log.isTraceEnabled()) {
                    log.trace("topkey: " + top);
                    log.trace("cfDiff > 0, seeking to range: " + newRange);
                }
                
                source.seek(newRange, seekColumnFamilies, includeColumnFamilies);
                continue;
            } else if (cfDiff < 0) {
                // need to move to the next row and try again
                // this op is destructive on row, but iz ok 'cause the continue will reset it
                //
                // We can provide the columnFamily to avoid an additional iteration that would just call seek
                // with the given columnFamily for this Iterator
                Key newStart = new Key(top.followingKey(PartialKey.ROW).getRow(row), this.columnFamily);
                
                // If we try to seek to a Key that it outside of our Range, we're done
                if (scanRange.afterEndKey(newStart)) {
                    return;
                }
                
                Range newRange = new Range(newStart, false, scanRange.getEndKey(), scanRange.isEndKeyInclusive());
                
                if (log.isTraceEnabled()) {
                    log.trace("topkey: " + top);
                    log.trace("cfDiff < 0, seeking to range: " + newRange);
                }
                
                source.seek(newRange, seekColumnFamilies, includeColumnFamilies);
                continue;
            }
            
            if (cqDiff > 0) {
                // need try and find our columnFamily
                Key newStart = new Key(row, columnFamily, valueMinPrefix);
                Range newRange = new Range(newStart, false, scanRange.getEndKey(), scanRange.isEndKeyInclusive());
                
                if (log.isTraceEnabled()) {
                    log.trace("topkey: " + top);
                    log.trace("cqDiff > 0, seeking to range: " + newRange);
                }
                
                source.seek(newRange, seekColumnFamilies, includeColumnFamilies);
                continue;
            } else if (cqDiff < 0) {
                // need to move to the next row and try again
                // this op is destructive on row, but iz ok 'cause the continue will reset it
                //
                // We can provide the columnFamily to avoid an additional iteration that would just call seek
                // with the given columnFamily for this Iterator
                Key newStart = new Key(top.followingKey(PartialKey.ROW).getRow(row), this.columnFamily);
                
                // If we try to seek to a Key that it outside of our Range, we're done
                if (scanRange.afterEndKey(newStart)) {
                    return;
                }
                
                Range newRange = new Range(newStart, false, scanRange.getEndKey(), scanRange.isEndKeyInclusive());
                
                if (log.isTraceEnabled()) {
                    log.trace("topkey: " + top);
                    log.trace("cqDiff < 0, seeking to range: " + newRange);
                }
                
                source.seek(newRange, seekColumnFamilies, includeColumnFamilies);
                continue;
            }
            
            if (this.scanRange.isStartKeyInclusive()) {
                if (!this.scanRange.isInfiniteStartKey() && top.compareTo(this.scanRange.getStartKey(), PartialKey.ROW_COLFAM_COLQUAL) < 0) {
                    source.next();
                    continue;
                }
            } else {
                if (!this.scanRange.isInfiniteStartKey() && top.compareTo(this.scanRange.getStartKey(), PartialKey.ROW_COLFAM_COLQUAL) <= 0) {
                    source.next();
                    continue;
                }
            }
            
            // A field index key's timestamp is accurate to the millisecond, so we can observe this
            // and remove all keys which don't satisfy the intra-day time range.
            if (!this.timeFilter.apply(top)) {
                if (log.isTraceEnabled()) {
                    log.trace("Ignoring key due to not occuring within time filter: " + top);
                }
                Range newRange;
                if (timeSeekingFilter != null
                                && (newRange = timeSeekingFilter.getSeekRange(top, this.scanRange.getEndKey(), this.scanRange.isEndKeyInclusive())) != null) {
                    source.seek(newRange, seekColumnFamilies, includeColumnFamilies);
                } else {
                    source.next();
                }
                continue;
            }
            
            if (!this.datatypeFilter.apply(top)) {
                if (log.isTraceEnabled()) {
                    log.trace("Ignoring key due to not occuring within datatype filter: " + top);
                }
                Range newRange;
                if (dataTypeSeekingFilter != null
                                && (newRange = dataTypeSeekingFilter.getSeekRange(top, this.scanRange.getEndKey(), this.scanRange.isEndKeyInclusive())) != null) {
                    source.seek(newRange, seekColumnFamilies, includeColumnFamilies);
                } else {
                    source.next();
                }
                continue;
            }
            
            if (this.compositePredicateFilters != null && !this.compositePredicateFilters.isEmpty()) {
                String colQual = top.getColumnQualifier().toString();
                String[] terms = colQual.substring(0, colQual.indexOf('\0')).split(CompositeUtils.SEPARATOR);
                String ingestType = colQual.substring(colQual.indexOf('\0') + 1, colQual.lastIndexOf('\0'));
                String colFam = top.getColumnFamily().toString();
                String fieldName = colFam.substring(colFam.indexOf('\0') + 1);
                
                CompositePredicateFilter compositePredicateFilter = (compositePredicateFilters.get(ingestType) != null) ? compositePredicateFilters.get(
                                ingestType).get(fieldName) : null;
                if (compositePredicateFilter != null && !compositePredicateFilter.keep(terms, top.getTimestamp())) {
                    if (log.isTraceEnabled())
                        log.trace("Ignoring key due to not passing the composite predicate filter: " + top);
                    source.next();
                    continue;
                }
            }
            
            // Aggregate the document. NOTE: This will advance the source iterator
            tk = buildDocument ? aggregation.apply(source, document, attributeFactory) : aggregation.apply(source, scanRange, seekColumnFamilies,
                            includeColumnFamilies);
            if (log.isTraceEnabled()) {
                log.trace("Doc size: " + this.document.size());
                log.trace("Returning pointer " + tk.toStringNoTime());
            }
        }
    }
    
    @Override
    public Key getTopKey() {
        return tk;
    }
    
    @Override
    public Value getTopValue() {
        return tv;
    }
    
    @Override
    public SortedKeyValueIterator<Key,Value> deepCopy(IteratorEnvironment env) {
        throw new UnsupportedOperationException("Cannot deep copy this iterator.");
    }
    
    @Override
    public void init(SortedKeyValueIterator<Key,Value> source, Map<String,String> options, IteratorEnvironment env) throws IOException {
        throw new UnsupportedOperationException("This iterator cannot be init'd. Please use the constructor.");
    }
    
    @Override
    public void seek(Range range, Collection<ByteSequence> columnFamilies, boolean inclusive) throws IOException {
        this.scanRange = buildIndexRange(range);
        
        if (log.isTraceEnabled()) {
            log.trace(this.toString() + " seek'ing to: " + this.scanRange + " from " + range);
        }
        
        source.seek(this.scanRange, this.seekColumnFamilies, this.includeColumnFamilies);
        next();
    }
    
    private final Text newColumnQualifier = new Text(new byte[128]);
    
    @Override
    public void move(Key pointer) throws IOException {
        if (this.hasTop() && this.getTopKey().compareTo(pointer) >= 0) {
            throw new IllegalStateException("Tried to called move when we were already at or beyond where we were told to move to: topkey=" + this.getTopKey()
                            + ", movekey=" + pointer);
        }
        
        newColumnQualifier.set(valueMinPrefix);
        Text id = pointer.getColumnFamily();
        newColumnQualifier.append(id.getBytes(), 0, id.getLength());
        
        Key nextKey = new Key(pointer.getRow(), columnFamily, newColumnQualifier);
        Key newTop = null;
        for (int i = 0; i < 256 && source.hasTop() && (newTop = source.getTopKey()).compareTo(nextKey) < 0; ++i)
            source.next();
        
        /*
         * We need to verify a few things after next()'ing a bunch and then seeking:
         * 
         * 1) We actually had data before even trying to move 2) The last returned key by the source is less than the one we want to be at 3) The source still
         * has data - we could get away without checking this, but why try and seek if we already know we have no more data?
         */
        if (newTop != null && newTop.compareTo(nextKey) < 0 && source.hasTop()) {
            Range r = new Range(nextKey, true, scanRange.getEndKey(), scanRange.isEndKeyInclusive());
            if (log.isTraceEnabled())
                log.trace(this.toString() + " move'ing to: " + r);
            source.seek(r, seekColumnFamilies, includeColumnFamilies);
        } else {
            if (log.isTraceEnabled())
                log.trace(this.toString() + " stepping its way to " + newTop);
        }
        
        if (log.isTraceEnabled()) {
            log.trace(this.toString() + " finished move. Now at " + (source.hasTop() ? source.getTopKey() : "null") + ", calling next()");
        }
        
        next();
    }
    
    protected void seek(SortedKeyValueIterator<Key,Value> source, Range r) throws IOException {
        source.seek(r, this.seekColumnFamilies, true);
    }
    
    /**
     * Permute a "Document" Range to the equivalent "Field Index" Range for a Field:Term
     * 
     * @param r
     * @return
     */
    protected Range buildIndexRange(Range r) {
        Key startKey = permuteRangeKey(r.getStartKey(), r.isStartKeyInclusive());
        Key endKey = permuteRangeKey(r.getEndKey(), r.isEndKeyInclusive());
        
        return new Range(startKey, r.isStartKeyInclusive(), endKey, r.isEndKeyInclusive());
    }
    
    /**
     * Permute a "Document" Key to an equivalent "Field Index" key for a Field:Term
     * 
     * @param rangeKey
     * @return
     */
    protected Key permuteRangeKey(Key rangeKey, boolean inclusive) {
        Key key = null;
        
        if (null != rangeKey) {
            // The term for this index iterator
            Text term = new Text(valueMinPrefix);
            
            // Build up term\x00type\x00uid for the new columnqualifier
            term = Util.appendText(term, rangeKey.getColumnFamily());
            
            // if not inclusive, then add a null byte to the end of the UID to ensure we go to the next one
            if (!inclusive) {
                term = Util.appendSuffix(term, (byte) 0);
            }
            
            key = new Key(rangeKey.getRow(), this.columnFamily, term);
        }
        
        return key;
    }
    
    @Override
    public Document document() {
        return document;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("IndexIterator: ");
        sb.append(this.columnFamily.toString().replace("\0", "\\x00"));
        sb.append(", ");
        sb.append(this.valueMinPrefix.toString().replace("\0", "\\x00"));
        
        return sb.toString();
    }
    
    @Override
    public void addCompositePredicates(Set<JexlNode> compositePredicates) {
        if (compositePredicateFilters != null) {
            // Assign composite predicates to their corresponding field index filters
            for (Map<String,CompositePredicateFilter> map : compositePredicateFilters.values())
                for (CompositePredicateFilter compositePredicateFilter : map.values())
                    compositePredicateFilter.addCompositePredicates(compositePredicates);
        }
    }
}

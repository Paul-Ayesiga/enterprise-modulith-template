package ug.co.smsone.search.internal;

import org.springframework.stereotype.Component;
import ug.co.smsone.search.SearchDoc;
import ug.co.smsone.search.SearchIndex;

/** The {@link SearchIndex} port over the store — producers stay one thin call away from indexed. */
@Component
class SearchIndexImpl implements SearchIndex {

    private final SearchIndexStore store;

    SearchIndexImpl(SearchIndexStore store) {
        this.store = store;
    }

    @Override
    public void upsert(SearchDoc doc) {
        store.upsert(doc);
    }

    @Override
    public void remove(String entityType, String entityId) {
        store.remove(entityType, entityId);
    }
}

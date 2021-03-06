/*
 * RESTHeart - the Web API for MongoDB
 * Copyright (C) SoftInstigate Srl
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.restheart.handlers.bulk;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.restheart.db.BulkOperationResult;
import org.restheart.db.DocumentDAO;
import org.restheart.handlers.PipedHttpHandler;
import org.restheart.handlers.RequestContext;
import org.restheart.handlers.RequestContext.DOC_ID_TYPE;
import org.restheart.utils.HttpStatus;
import org.restheart.utils.ResponseHelper;

/**
 *
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
public class BulkPostCollectionHandler extends PipedHttpHandler {

    private final DocumentDAO documentDAO;

    /**
     * Creates a new instance of BulkPostCollectionHandler
     */
    public BulkPostCollectionHandler() {
        this(null, new DocumentDAO());
    }

    /**
     * Creates a new instance of BulkPostCollectionHandler
     * @param documentDAO
     */
    public BulkPostCollectionHandler(DocumentDAO documentDAO) {
        this(null, new DocumentDAO());
    }

    /**
     * Creates a new instance of BulkPostCollectionHandler
     * @param next
     */
    public BulkPostCollectionHandler(PipedHttpHandler next) {
        this(next, new DocumentDAO());
    }

    /**
     * Creates a new instance of BulkPostCollectionHandler
     * @param next
     * @param documentDAO
     */
    public BulkPostCollectionHandler(PipedHttpHandler next, DocumentDAO documentDAO) {
        super(next);
        this.documentDAO = documentDAO;
    }

    /**
     *
     * @param exchange
     * @param context
     * @throws Exception
     */
    @Override
    public void handleRequest(HttpServerExchange exchange, RequestContext context) throws Exception {
        DBObject content = context.getContent();

        // expects an an array
        if (content == null || !(content instanceof BasicDBList)) {
            throw new RuntimeException("error, this handler expects an array of objects");
        }

        BasicDBList documents = (BasicDBList) content;

        if (!checkIds(exchange, context, documents)) {
            // if check fails, exchange has been closed
            return;
        }

        BulkOperationResult result = this.documentDAO
                .bulkUpsertDocumentsPost(context.getDBName(),
                        context.getCollectionName(),
                        documents,
                        context.getShardKey());

        context.setDbOperationResult(result);

        // inject the etag
        if (result.getEtag() != null) {
            exchange.getResponseHeaders().put(Headers.ETAG, result.getEtag().toString());
        }

        if (context.getWarnings() != null && !context.getWarnings().isEmpty()) {
            //sendWarnings(result.getHttpCode(), exchange, context);
        } else {
            exchange.setStatusCode(result.getHttpCode());
        }

        BulkResultRepresentationFactory bprf = new BulkResultRepresentationFactory();
        
        bprf.sendRepresentation(exchange, context, 
                bprf.getRepresentation(exchange, context, result));
        
        if (getNext() != null) {
            getNext().handleRequest(exchange, context);
        }

        exchange.endExchange();
    }

    private boolean checkIds(HttpServerExchange exchange, RequestContext context, BasicDBList documents) {
        boolean ret = true;

        for (Object document : documents) {
            if (!checkId(exchange, context, (BasicDBObject) document)) {
                ret = false;
                break;
            }
        }

        return ret;
    }

    private boolean checkId(HttpServerExchange exchange, RequestContext context, BasicDBObject document) {
        if (document.get("_id") != null && document.get("_id") instanceof String
                && RequestContext.isReservedResourceDocument(context.getType(), (String) document.get("_id"))) {
            ResponseHelper.endExchangeWithMessage(exchange,
                    HttpStatus.SC_FORBIDDEN,
                    "id is reserved: " + document.get("_id"));
            return false;
        }

        if (document.containsField("_id")) {
            if (!(context.getDocIdType() == DOC_ID_TYPE.OID
                    || context.getDocIdType() == DOC_ID_TYPE.STRING_OID)) {
                ResponseHelper.endExchangeWithMessage(exchange,
                        HttpStatus.SC_NOT_ACCEPTABLE,
                        "_id in content body is mandatory for documents with id type " + context.getDocIdType().name());
                return false;
            }
        }

        return true;
    }
}

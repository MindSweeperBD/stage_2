# Ingestion and Indexing Integration Contract

**Version:** 1.0  
**Project:** Mindsweeper Stage 2

## 1. Datalake structure

The Ingestion Service stores downloaded books using the book-based totally layout:

```text
book_datalake/
    1342/
        header.txt
        body.txt
        .complete
```

Each e book has its own directory, diagnosed by its advantageous Gutenberg e book ID.

## 2. File contents

- `header.Txt`: Project Gutenberg metadata and introductory textual content before the START marker.
- `frame.Txt`: Main e book content between the START and END markers.
- `.Entire`: Marker indicating that the e book was fully stored.

All text documents use UTF-8 encoding.

## 3. Readiness

The Indexing Service need to no longer technique a e-book till it's miles whole.

A e-book is taken into consideration to be had when its listing carries `header.Txt`, `body.Txt`, and `.Complete`, and its frame file is nonempty.

The of entirety marker is posted after the header and body files have been written.

## 4. API communication

The Control Module can request ingestion the usage of:

`POST /ingest/book_id`

The operation is synchronous. A a hit HTTP 200 response means that the e book has been stored.

Possible a hit statuses:

- `downloaded`: The ebook has just been downloaded and stored.
- `already_downloaded`: The e book became already complete and has no longer been rewritten.

The Indexing Service can test readiness the use of:

`GET /ingest/repute/book_id`

## 5. Failure recovery

If a procedure fails earlier than publishing `.Entire`, the book isn't considered available.

A later ingestion request can overwrite unfinished files and complete the operation.

Temporary files left by interrupted methods may also remain on disk and are blanketed in bodily garage measurements.

## 6. Concurrency

The provider helps concurrent HTTP requests inside one Java technique.

Requests for the equal ebook ID are synchronized to keep away from reproduction downloads inside that procedure.

**Only one ingestion creator technique is supported.** Multiple replicas writing to the same datalake require extra coordination.

## 7. Responsibility boundaries

The Ingestion Service downloads, splits, and shops books.

The Indexing Service handles tokenization, normalization, postings, and index production.

The Search Service approaches seek requests.

The Control Module coordinates the provider workflow.

## 8. Stage 3 considerations

A disbursed deployment would require shared storage, interprocess assignment possession, distributed locking or equivalent coordination, and versioned ebook of saved data.

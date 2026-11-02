package com.spydrone.orthanc_scan_producer.scan;

/** What {@link LotClient} learned about a lot from orthanc-scan-consumer. */
public sealed interface LotLookup {

	/** The consumer has the lot. */
	record Found(LotState lot) implements LotLookup {
	}

	/** The consumer answered 404: it doesn't know the lot. */
	record NotFound() implements LotLookup {
	}

	/** The consumer couldn't be asked: it failed, timed out, gave an empty answer, or its circuit is open. */
	record Unavailable() implements LotLookup {
	}
}

package ru.homeserver.photoshare.homeserver.search;

import java.io.IOException;

/** An intentional indexing exclusion, not a processing failure. */
final class MediaSkippedException extends IOException {
    MediaSkippedException(String reason) { super(reason); }
}

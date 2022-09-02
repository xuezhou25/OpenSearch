/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.admin.indices.segments;

import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.action.support.broadcast.BroadcastRequest;
import org.opensearch.common.Strings;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.opensearch.action.ValidateActions.addValidationError;

/**
 * Transport request for retrieving PITs segment information
 */
public class PitSegmentsRequest extends BroadcastRequest<PitSegmentsRequest> {
    private boolean verbose = false;
    private final List<String> pitIds = new ArrayList<>();

    public PitSegmentsRequest() {
        this(Strings.EMPTY_ARRAY);
    }

    public PitSegmentsRequest(StreamInput in) throws IOException {
        super(in);
        pitIds.addAll(Arrays.asList(in.readStringArray()));
        verbose = in.readBoolean();
    }

    public PitSegmentsRequest(String... pitIds) {
        super(pitIds);
        this.pitIds.addAll(Arrays.asList(pitIds));
    }

    /**
     * <code>true</code> if detailed information about each segment should be returned,
     * <code>false</code> otherwise.
     */
    public boolean isVerbose() {
        return verbose;
    }

    /**
     * Sets the <code>verbose</code> option.
     * @see #isVerbose()
     */
    public void setVerbose(boolean v) {
        verbose = v;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeStringArrayNullable((pitIds == null) ? null : pitIds.toArray(new String[pitIds.size()]));
        out.writeBoolean(verbose);
    }

    public List<String> getPitIds() {
        return Collections.unmodifiableList(pitIds);
    }

    public void clearAndSetPitIds(List<String> pitIds) {
        this.pitIds.clear();
        this.pitIds.addAll(pitIds);
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (pitIds == null || pitIds.isEmpty()) {
            validationException = addValidationError("no pit ids specified", validationException);
        }
        return validationException;
    }
}

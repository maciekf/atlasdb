/*
 * Copyright 2015 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.paxos;

import java.util.Collection;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import com.palantir.common.annotation.Inclusive;

@Path("/learner")
public interface PaxosLearner {

    /**
     * Learn given value for the seq-th round.
     *
     * @param seq round in question
     * @param val value learned for that round
     */
    @POST
    @Path("learn/{seq:.+}")
    @Consumes(MediaType.APPLICATION_JSON)
    void learn(@PathParam("seq") long seq, PaxosValue val);

    /**
     * Returns learned value or null if non-exists.
     */
    @Nullable
    @GET
    @Path("learned-value/{seq:.+}")
    @Produces(MediaType.APPLICATION_JSON)
    PaxosValue getLearnedValue(@PathParam("seq") long seq);

    /**
     * Returns the learned value for the greatest known round or null if nothing has been learned.
     */
    @Nullable
    @GET
    @Path("greatest-learned-value")
    @Produces(MediaType.APPLICATION_JSON)
    PaxosValue getGreatestLearnedValue();

    /**
     * Returns some collection of learned values since the seq-th round (inclusive).
     *
     * @param seq lower round cutoff for returned values
     * @return some set of learned values for rounds since the seq-th round
     */
    @Nonnull
    @GET
    @Path("learned-values-since/{seq:.+}")
    @Produces(MediaType.APPLICATION_JSON)
    Collection<PaxosValue> getLearnedValuesSince(@PathParam("seq") @Inclusive long seq);

}

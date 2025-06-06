/*
 * Copyright 2022 VMware, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.micrometer.prometheus;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.Histogram;
import io.micrometer.core.instrument.distribution.TimeWindowFixedBoundaryHistogram;
import io.micrometer.core.instrument.util.TimeUtils;
import io.prometheus.client.exemplars.Exemplar;
import io.prometheus.client.exemplars.HistogramExemplarSampler;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Internal {@link Histogram} implementation for Prometheus that handles {@link Exemplar
 * exemplars}.
 *
 * @author Jonatan Ivanov
 */
class PrometheusHistogram extends TimeWindowFixedBoundaryHistogram {

    private final double @Nullable [] buckets;

    private final @Nullable AtomicReferenceArray<Exemplar> exemplars;

    private final @Nullable AtomicReference<Exemplar> lastExemplar;

    private final @Nullable HistogramExemplarSampler exemplarSampler;

    PrometheusHistogram(Clock clock, DistributionStatisticConfig config,
            @Nullable HistogramExemplarSampler exemplarSampler) {
        super(clock, DistributionStatisticConfig.builder()
            // effectively never rolls over
            .expiry(Duration.ofDays(1825))
            .bufferLength(1)
            .build()
            .merge(config), true);

        this.exemplarSampler = exemplarSampler;
        if (exemplarSampler != null) {
            double[] originalBuckets = getBuckets();
            if (originalBuckets[originalBuckets.length - 1] != Double.POSITIVE_INFINITY) {
                this.buckets = Arrays.copyOf(originalBuckets, originalBuckets.length + 1);
                this.buckets[buckets.length - 1] = Double.POSITIVE_INFINITY;
            }
            else {
                this.buckets = originalBuckets;
            }
            this.exemplars = new AtomicReferenceArray<>(this.buckets.length);
            this.lastExemplar = new AtomicReference<>();
        }
        else {
            this.buckets = null;
            this.exemplars = null;
            this.lastExemplar = null;
        }
    }

    @EnsuresNonNullIf({ "exemplarSampler", "buckets", "exemplars", "lastExemplar" })
    boolean isExemplarsEnabled() {
        return exemplarSampler != null && buckets != null && exemplars != null && lastExemplar != null;
    }

    @Override
    public void recordDouble(double value) {
        super.recordDouble(value);
        if (isExemplarsEnabled()) {
            updateExemplar(value, null, null);
        }
    }

    @Override
    public void recordLong(long value) {
        super.recordLong(value);
        if (isExemplarsEnabled()) {
            updateExemplar((double) value, NANOSECONDS, SECONDS);
        }
    }

    @RequiresNonNull({ "exemplarSampler", "buckets", "exemplars", "lastExemplar" })
    private void updateExemplar(double value, @Nullable TimeUnit sourceUnit, @Nullable TimeUnit destinationUnit) {
        int index = leastLessThanOrEqualTo(value);
        index = (index == -1) ? exemplars.length() - 1 : index;
        updateExemplar(value, sourceUnit, destinationUnit, index);
    }

    @RequiresNonNull({ "exemplarSampler", "buckets", "exemplars", "lastExemplar" })
    private void updateExemplar(double value, @Nullable TimeUnit sourceUnit, @Nullable TimeUnit destinationUnit,
            int index) {
        double bucketFrom = (index == 0) ? Double.NEGATIVE_INFINITY : buckets[index - 1];
        double bucketTo = buckets[index];
        Exemplar previusBucketExemplar;
        Exemplar previousLastExemplar;
        Exemplar nextExemplar;

        double exemplarValue = (sourceUnit != null && destinationUnit != null)
                ? TimeUtils.convert(value, sourceUnit, destinationUnit) : value;
        do {
            previusBucketExemplar = exemplars.get(index);
            previousLastExemplar = lastExemplar.get();
            nextExemplar = exemplarSampler.sample(exemplarValue, bucketFrom, bucketTo, previusBucketExemplar);
        }
        while (nextExemplar != null && nextExemplar != previusBucketExemplar
                && !(exemplars.compareAndSet(index, previusBucketExemplar, nextExemplar)
                        && lastExemplar.compareAndSet(previousLastExemplar, nextExemplar)));
    }

    Exemplar @Nullable [] exemplars() {
        if (isExemplarsEnabled()) {
            Exemplar[] exemplarsArray = new Exemplar[this.exemplars.length()];
            for (int i = 0; i < this.exemplars.length(); i++) {
                exemplarsArray[i] = this.exemplars.get(i);
            }

            return exemplarsArray;
        }
        else {
            return null;
        }
    }

    @Nullable Exemplar lastExemplar() {
        if (isExemplarsEnabled()) {
            return this.lastExemplar.get();
        }
        else {
            return null;
        }
    }

    /**
     * The least bucket that is less than or equal to a sample.
     */
    @RequiresNonNull("buckets")
    private int leastLessThanOrEqualTo(double key) {
        int low = 0;
        int high = buckets.length - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (buckets[mid] < key)
                low = mid + 1;
            else if (buckets[mid] > key)
                high = mid - 1;
            else
                return mid; // exact match
        }

        return low < buckets.length ? low : -1;
    }

}

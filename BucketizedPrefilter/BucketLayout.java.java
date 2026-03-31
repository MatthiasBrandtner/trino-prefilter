package io.trino.sql.planner.optimizations;

import static java.lang.Math.floorDiv;

public final class BucketLayout
{
    private final long origin;
    private final long width;

    public BucketLayout(long origin, long width)
    {
        if (width <= 0) {
            throw new IllegalArgumentException("bucket width must be > 0");
        }
        this.origin = origin;
        this.width = width;
    }

    public long getOrigin()
    {
        return origin;
    }

    public long getWidth()
    {
        return width;
    }

    /**
     * Ordnet einen Zeitwert einem Bucket zu.
     */
    public long bucketId(long value)
    {
        return floorDiv(value - origin, width);
    }

    /**
     * Start des Buckets mit ID bucketId.
     */
    public long bucketStart(long bucketId)
    {
        return origin + bucketId * width;
    }

    /**
     * Ende des Buckets mit ID bucketId.
     * Buckets sind als [start, end) modelliert.
     */
    public long bucketEnd(long bucketId)
    {
        return bucketStart(bucketId) + width;
    }

    /**
     * Prüft, ob ein Wert in einem bestimmten Bucket liegt.
     */
    public boolean isInBucket(long value, long bucketId)
    {
        long start = bucketStart(bucketId);
        long end = bucketEnd(bucketId);
        return value >= start && value < end;
    }

    /**
     * Rundet einen Wert auf den Start seines Buckets herunter.
     */
    public long floorToBucketStart(long value)
    {
        return bucketStart(bucketId(value));
    }

    /**
     * Prüft, ob zwei Buckets sich überschneiden.
     * Bei disjunkten Standard-Buckets ist das normalerweise false,
     * kann aber für spätere Erweiterungen nützlich sein.
     */
    public boolean bucketsOverlap(long leftBucketId, long rightBucketId)
    {
        long leftStart = bucketStart(leftBucketId);
        long leftEnd = bucketEnd(leftBucketId);
        long rightStart = bucketStart(rightBucketId);
        long rightEnd = bucketEnd(rightBucketId);

        return leftStart < rightEnd && rightStart < leftEnd;
    }

    @Override
    public String toString()
    {
        return "BucketLayout{" +
                "origin=" + origin +
                ", width=" + width +
                '}';
    }
}
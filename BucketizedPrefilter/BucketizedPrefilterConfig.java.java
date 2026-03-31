package io.trino.sql.planner.optimizations;

public record BucketizedPrefilterConfig(long bucketWidth)
{
    public BucketizedPrefilterConfig
    {
        if (bucketWidth <= 0) {
            throw new IllegalArgumentException("bucketWidth must be > 0");
        }
    }
}
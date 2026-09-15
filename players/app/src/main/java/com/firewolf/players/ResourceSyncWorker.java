package com.firewolf.players;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class ResourceSyncWorker extends Worker {
    public ResourceSyncWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        CatalogRepository.SyncResult result = CatalogRepository.get(getApplicationContext()).syncNow();
        return "离线片单可用".equals(result.status) ? Result.retry() : Result.success();
    }
}

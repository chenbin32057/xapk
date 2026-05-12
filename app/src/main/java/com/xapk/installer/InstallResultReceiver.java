package com.xapk.installer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

public class InstallResultReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        Intent resultIntent = new Intent(context, MainActivity.class);
        resultIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                resultIntent.putExtra("install_result", "success");
                break;
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // Forward the user confirmation activity
                Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirmIntent);
                }
                return;
            default:
                resultIntent.putExtra("install_result", "failed");
                resultIntent.putExtra("error_message", message);
                break;
        }
        context.startActivity(resultIntent);
    }
}

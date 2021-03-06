package com.instaclustr.cassandra.backup.embedded.gcp;

import static org.testng.Assert.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.instaclustr.cassandra.backup.gcp.GCPModule;
import com.instaclustr.cassandra.backup.gcp.GCPModule.GoogleStorageFactory;
import com.instaclustr.cassandra.backup.impl.backup.BackupOperationRequest;
import com.instaclustr.kubernetes.KubernetesApiModule;
import com.instaclustr.kubernetes.KubernetesService;
import com.instaclustr.threading.ExecutorsModule;
import io.kubernetes.client.ApiException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Test(groups = {
    "k8sTest",
    "googleTest",
})
public class KubernetesGoogleStorageBackupRestoreTest extends BaseGoogleStorageBackupRestoreTest {

    private static final String GCP_SIDECAR_SECRET_NAME = "test-gcp-sidecar-secret";

    final String[] backupArgs = new String[]{
        "backup",
        "--jmx-service", "127.0.0.1:7199",
        "--storage-location=gcp://" + BUCKET_NAME + "/cluster/test-dc/1",
        "--data-directory=" + cassandraDir.toAbsolutePath().toString() + "/data",
        "--k8s-backup-secret-name=" + GCP_SIDECAR_SECRET_NAME
    };

    final String[] backupArgsWithSnapshotName = new String[]{
        "backup",
        "--jmx-service", "127.0.0.1:7199",
        "--storage-location=gcp://" + BUCKET_NAME + "/cluster/test-dc/1",
        "--snapshot-tag=stefansnapshot",
        "--data-directory=" + cassandraDir.toAbsolutePath().toString() + "/data",
        "--k8s-backup-secret-name=" + GCP_SIDECAR_SECRET_NAME
    };

    final String[] restoreArgs = new String[]{
        "restore",
        "--data-directory=" + cassandraRestoredDir.toAbsolutePath().toString() + "/data",
        "--config-directory=" + cassandraRestoredConfigDir.toAbsolutePath().toString(),
        "--snapshot-tag=stefansnapshot",
        "--storage-location=gcp://" + BUCKET_NAME + "/cluster/test-dc/1",
        "--update-cassandra-yaml=true",
        "--k8s-backup-secret-name=" + GCP_SIDECAR_SECRET_NAME
    };

    final String[] commitlogBackupArgs = new String[]{
        "commitlog-backup",
        "--storage-location=gcp://" + BUCKET_NAME + "/cluster/test-dc/1",
        "--data-directory=" + cassandraDir.toAbsolutePath().toString() + "/data",
        "--k8s-backup-secret-name=" + GCP_SIDECAR_SECRET_NAME
    };

    final String[] commitlogRestoreArgs = new String[]{
        "commitlog-restore",
        "--data-directory=" + cassandraRestoredDir.toAbsolutePath().toString() + "/data",
        "--config-directory=" + cassandraRestoredConfigDir.toAbsolutePath().toString(),
        "--storage-location=gcp://" + BUCKET_NAME + "/cluster/test-dc/1",
        "--commitlog-download-dir=" + target("commitlog_download_dir"),
        "--k8s-backup-secret-name=" + GCP_SIDECAR_SECRET_NAME
    };

    @Inject
    public KubernetesService kubernetesService;

    @Inject
    public GoogleStorageFactory googleStorageFactory;

    @BeforeMethod
    public void setup() throws Exception {

        final List<Module> modules = new ArrayList<Module>() {{
            add(new KubernetesApiModule());
            add(new GCPModule());
            add(new ExecutorsModule());
        }};

        final Injector injector = Guice.createInjector(modules);
        injector.injectMembers(this);

        init();
    }

    @AfterMethod
    public void teardown() throws Exception {
        destroy();
    }

    @Override
    protected void init() throws ApiException, IOException {
        System.setProperty("kubernetes.client", "true");

        assertNotNull(kubernetesService);

        kubernetesService.createSecret(GCP_SIDECAR_SECRET_NAME, new HashMap<String, String>() {{
            put("gcp", new String(Files.readAllBytes(Paths.get(System.getProperty("google.application.credentials")))));
        }});
    }

    @Override
    protected void destroy() throws ApiException {
        kubernetesService.deleteSecret(GCP_SIDECAR_SECRET_NAME);
        System.setProperty("kubernetes.client", "false");
    }

    @Override
    protected BackupOperationRequest getBackupOperationRequest() {
        final BackupOperationRequest backupOperationRequest = new BackupOperationRequest();
        backupOperationRequest.k8sBackupSecretName = GCP_SIDECAR_SECRET_NAME;
        return backupOperationRequest;
    }

    @Override
    protected String[][] getProgramArguments() {
        return new String[][]{
            backupArgs,
            backupArgsWithSnapshotName,
            commitlogBackupArgs,
            restoreArgs,
            commitlogRestoreArgs
        };
    }

    @Override
    public GoogleStorageFactory getGoogleStorageFactory() {
        return googleStorageFactory;
    }

    @Test
    public void testBackupAndRestore() throws Exception {
        test();
    }
}

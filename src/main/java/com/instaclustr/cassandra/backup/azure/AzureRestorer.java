package com.instaclustr.cassandra.backup.azure;

import static java.lang.String.format;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.instaclustr.cassandra.backup.azure.AzureModule.CloudStorageAccountFactory;
import com.instaclustr.cassandra.backup.impl.RemoteObjectReference;
import com.instaclustr.cassandra.backup.impl.restore.RestoreCommitLogsOperationRequest;
import com.instaclustr.cassandra.backup.impl.restore.RestoreOperationRequest;
import com.instaclustr.cassandra.backup.impl.restore.Restorer;
import com.instaclustr.threading.Executors.ExecutorServiceSupplier;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.BlobListingDetails;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.ListBlobItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AzureRestorer extends Restorer {

    private static final Logger logger = LoggerFactory.getLogger(AzureRestorer.class);

    private final CloudBlobContainer blobContainer;

    private final CloudBlobClient cloudBlobClient;

    private final CloudStorageAccount cloudStorageAccount;

    @AssistedInject
    public AzureRestorer(final CloudStorageAccountFactory cloudStorageAccountFactory,
                         final ExecutorServiceSupplier executorServiceSupplier,
                         @Assisted final RestoreOperationRequest request) throws Exception {
        super(request, executorServiceSupplier);

        cloudStorageAccount = cloudStorageAccountFactory.build(request);
        cloudBlobClient = cloudStorageAccount.createCloudBlobClient();

        this.blobContainer = cloudBlobClient.getContainerReference(request.storageLocation.bucket);
    }

    @AssistedInject
    public AzureRestorer(final CloudStorageAccountFactory cloudStorageAccountFactory,
                         final ExecutorServiceSupplier executorServiceSupplier,
                         @Assisted final RestoreCommitLogsOperationRequest request) throws Exception {
        super(request, executorServiceSupplier);

        cloudStorageAccount = cloudStorageAccountFactory.build(request);
        cloudBlobClient = cloudStorageAccount.createCloudBlobClient();

        this.blobContainer = cloudBlobClient.getContainerReference(request.storageLocation.bucket);
    }

    @Override
    public RemoteObjectReference objectKeyToRemoteReference(final Path objectKey) throws StorageException, URISyntaxException {
        final String canonicalPath = resolveRemotePath(objectKey);
        return new AzureRemoteObjectReference(objectKey, canonicalPath, this.blobContainer.getBlockBlobReference(canonicalPath));
    }

    @Override
    public void downloadFile(final Path localPath, final RemoteObjectReference objectReference) throws Exception {
        final CloudBlockBlob blob = ((AzureRemoteObjectReference) objectReference).blob;
        Files.createDirectories(localPath.getParent());
        blob.downloadToFile(localPath.toAbsolutePath().toString());
    }

    @Override
    public void consumeFiles(final RemoteObjectReference prefix,
                             final Consumer<RemoteObjectReference> consumer) throws Exception {
        final AzureRemoteObjectReference azureRemoteObjectReference = (AzureRemoteObjectReference) prefix;

        final String blobPrefix = Paths.get(request.storageLocation.clusterId)
            .resolve(request.storageLocation.datacenterId)
            .resolve(request.storageLocation.nodeId)
            .resolve(azureRemoteObjectReference.getObjectKey()).toString();

        final String pattern = format("^/%s/%s/%s/%s/",
                                      request.storageLocation.bucket,
                                      request.storageLocation.clusterId,
                                      request.storageLocation.datacenterId,
                                      request.storageLocation.nodeId);

        final Pattern containerPattern = Pattern.compile(pattern);

        final Iterable<ListBlobItem> blobItemsIterable = blobContainer.listBlobs(blobPrefix,
                                                                                 true,
                                                                                 EnumSet.noneOf(BlobListingDetails.class),
                                                                                 null,
                                                                                 null);

        for (final ListBlobItem listBlobItem : blobItemsIterable) {
            try {
                consumer.accept(objectKeyToRemoteReference(Paths.get(containerPattern.matcher(listBlobItem.getUri().getPath()).replaceFirst(""))));
            } catch (StorageException | URISyntaxException e) {
                logger.error("Failed to generate objectKey for blob item \"{}\".", listBlobItem.getUri(), e);

                throw e;
            }
        }
    }

    @Override
    public void cleanup() {
        // Nothing to cleanup
    }
}

/*
*
*   Ambit Client
*
*   Ambit Client is licensed by GPL v3 as specified hereafter. Additional components may ship
*   with some other licence as will be specified therein.
*
*   Copyright (C) 2016 KinkyDesign
*
*   This program is free software: you can redistribute it and/or modify
*   it under the terms of the GNU General Public License as published by
*   the Free Software Foundation, either version 3 of the License, or
*   (at your option) any later version.
*
*   This program is distributed in the hope that it will be useful,
*   but WITHOUT ANY WARRANTY; without even the implied warranty of
*   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*   GNU General Public License for more details.
*
*   You should have received a copy of the GNU General Public License
*   along with this program.  If not, see <http://www.gnu.org/licenses/>.
*
*   Source code:
*   The source code of Ambit Client is available on github at:
*   https://github.com/KinkyDesign/AmbitClient
*   All source files of Ambit Client that are stored on github are licensed
*   with the aforementioned licence.
*
 */
package org.jaqpot.ambitclient;

import org.asynchttpclient.AsyncHttpClient;
import org.jaqpot.ambitclient.consumer.*;
import org.jaqpot.ambitclient.exception.AmbitClientException;
import org.jaqpot.ambitclient.model.BundleData;
import org.jaqpot.ambitclient.model.dataset.Dataset;
import org.jaqpot.ambitclient.model.dto.ambit.AmbitTask;
import org.jaqpot.ambitclient.model.dto.ambit.ProtocolCategory;
import org.jaqpot.ambitclient.model.dto.bundle.BundleProperties;
import org.jaqpot.ambitclient.model.dto.bundle.BundleSubstances;
import org.jaqpot.ambitclient.model.dto.study.Studies;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * @author Angelos Valsamis
 * @author Charalampos Chomenidis
 */
public class AmbitClientImpl implements AmbitClient {

    private static final String MOPAC_COMMANDS = "PM3 NOINTER MMOK BONDS MULLIK GNORM=1.0 T=30.00M";
    private static final long TIMEOUT = 5000L;

    private final DatasetResourceConsumer datasetConsumer;
    private final TaskResourceConsumer taskConsumer;
    private final AlgorithmResourceConsumer algorithmConsumer;
    private final BundleResourceConsumer bundleConsumer;
    private final SubstanceResourceConsumer substanceConsumer;
    private final SubstanceOwnerResourceConsumer substanceOwnerResourceConsumer;
    private final AsyncHttpClient client;

    public AmbitClientImpl(DatasetResourceConsumer datasetConsumer, TaskResourceConsumer taskConsumer, AlgorithmResourceConsumer algorithmConsumer, BundleResourceConsumer bundleConsumer, SubstanceResourceConsumer substanceConsumer, SubstanceOwnerResourceConsumer substanceOwnerResourceConsumer, AsyncHttpClient client) {
        this.datasetConsumer = datasetConsumer;
        this.taskConsumer = taskConsumer;
        this.algorithmConsumer = algorithmConsumer;
        this.bundleConsumer = bundleConsumer;
        this.substanceConsumer = substanceConsumer;
        this.substanceOwnerResourceConsumer = substanceOwnerResourceConsumer;
        this.client = client;
    }

    @Override
    public CompletableFuture<Dataset> generateMopacDescriptors(String pdbFile, String subjectId) {
        byte[] file;
        if (pdbFile.startsWith("data:")) {
            String base64pdb = pdbFile.split(",")[1];
            file = Base64.getDecoder().decode(base64pdb.getBytes());
        } else {
            try {
                URL pdbURL = new URL(pdbFile);
                file = inputStreamToByteArray(pdbURL.openStream());
            } catch (MalformedURLException ex) {
                throw new AmbitClientException("Invalid .pdb file url", ex);
            } catch (IOException ex) {
                throw new AmbitClientException("IO Error when trying to download .pdb file", ex);
            }
        }

        CompletableFuture<AmbitTask> result = datasetConsumer.createDatasetByPDB(file, subjectId);
        return result
                .thenCompose((t) -> taskConsumer.waitTask(t.getId(), TIMEOUT, subjectId))
                .thenCompose((t) -> {
                    String datasetURI = t.getResult();
                    Map<String, List<String>> parameters = new HashMap<>();
                    parameters.put("dataset_uri", Arrays.asList(datasetURI));
                    parameters.put("mopac_commands", Arrays.asList(MOPAC_COMMANDS));
                    return algorithmConsumer.train("ambit2.mopac.MopacOriginalStructure", parameters, subjectId);
                })
                .thenCompose(t -> taskConsumer.waitTask(t.getId(), TIMEOUT, subjectId))
                .thenCompose(t -> datasetConsumer.getDatasetById(t.getResult().split("dataset/")[1], subjectId));
    }

    @Override
    public CompletableFuture<String> createBundle(BundleData bundleData, String username, String subjectId) {
        String substanceOwner = bundleData.getSubstanceOwner();
        if (substanceOwner == null || substanceOwner.isEmpty()) {
            throw new AmbitClientException("Field substanceOwner cannot be empty.");
        }

        return bundleConsumer.createBundle(bundleData.getDescription(), username, substanceOwner, subjectId)
                .thenCompose(t -> taskConsumer.waitTask(t.getId(), TIMEOUT, subjectId))
                .thenApply(t -> {
                    bundleData.setBundleUri(t.getResult());
                    bundleData.setBundleId(t.getResult().split("bundle/")[1]);
                    return bundleData;
                })
                .thenCompose((bd) -> {
                    if (bd.getSubstances() == null || bd.getSubstances().isEmpty()) {
                        return substanceOwnerResourceConsumer.getOwnerSubstances(bd.getSubstanceOwner(), subjectId);
                    }
                    return CompletableFuture.supplyAsync(() -> bd.getSubstances());
                })
                .thenApply((substances) -> {
                    bundleData.setSubstances(substances);
                    return bundleData;
                })
                .thenCompose((BundleData bd) -> {
                    List<CompletableFuture<AmbitTask>> completableFutureList = new LinkedList<>();
                    for (String substance : bd.getSubstances()) {
                        completableFutureList.add(bundleConsumer.putSubstanceByBundleId(bd.getBundleId(), substance, subjectId)
                                .thenCompose(t -> taskConsumer.waitTask(t.getId(), TIMEOUT, subjectId)));
                    }
                    return CompletableFuture.allOf((completableFutureList.toArray(new CompletableFuture[completableFutureList.size()])));
                })
                .thenCompose((Void v) -> {
                    Map<String, List<String>> properties = bundleData.getProperties();
                    if (properties == null || properties.isEmpty()) {
                        properties = new HashMap<>();
                        for (ProtocolCategory category : ProtocolCategory.values()) {
                            String topCategoryName = category.getTopCategory();
                            String categoryName = category.name();

                            if (properties.containsKey(topCategoryName)) {
                                List<String> categoryValues = properties.get(topCategoryName);
                                categoryValues.add(categoryName);
                                properties.put(topCategoryName, categoryValues);
                            } else {
                                List<String> categoryValues = new ArrayList<>();
                                categoryValues.add(categoryName);
                                properties.put(topCategoryName, categoryValues);
                            }
                        }
                    }
                    List<CompletableFuture<AmbitTask>> completableFutureList = new LinkedList<>();
                    for (String topCategory : properties.keySet()) {
                        List<String> subCategories = properties.get(topCategory);
                        for (String subCategory : subCategories) {
                            completableFutureList.add(bundleConsumer.putPropertyByBundleId(bundleData.getBundleId(), topCategory, subCategory, subjectId)
                                    .thenCompose(s -> taskConsumer.waitTask(s.getId(), TIMEOUT, subjectId)));
                        }
                    }
                    return CompletableFuture.allOf((completableFutureList.toArray(new CompletableFuture[completableFutureList.size()])));
                })
                .thenApply((Void v) -> bundleData.getBundleUri());
    }

    @Override
    public CompletableFuture<Dataset> getDataset(String datasetId, String subjectId) {
        return datasetConsumer.getDatasetById(datasetId, subjectId);
    }

    @Override
    public CompletableFuture<Dataset> getDatasetStructures(String datasetId, String subjectId) {
        return datasetConsumer.getStructuresByDatasetId(datasetId, subjectId);
    }

    @Override
    public CompletableFuture<BundleSubstances> getBundleSubstances(String bundleId, String subjectId) {
        return bundleConsumer.getSubstancesByBundleId(bundleId, subjectId);

    }

    @Override
    public CompletableFuture<Studies> getSubstanceStudies(String substanceId, String subjectId) {
        return substanceConsumer.getStudiesBySubstanceId(substanceId, subjectId);
    }

    @Override
    public CompletableFuture<BundleProperties> getBundleProperties(String bundleId, String subjectId) {
        return bundleConsumer.getPropertiesByBundleId(bundleId, subjectId);
    }

    private byte[] inputStreamToByteArray(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int nRead;
        byte[] data = new byte[8192];

        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        buffer.flush();

        return buffer.toByteArray();
    }

    @Override
    public void close() throws IOException {
        this.client.close();
    }
}

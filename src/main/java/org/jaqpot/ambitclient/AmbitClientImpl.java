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
import java.util.logging.Logger;

/**
 * @author Angelos Valsamis
 * @author Charalampos Chomenidis
 */
public class AmbitClientImpl implements AmbitClient {

    private static final Logger LOG = Logger.getLogger(AmbitClientImpl.class.getName());

    private static final String MOPAC_COMMANDS = "PM3 NOINTER MMOK BONDS MULLIK GNORM=1.0 T=30.00M";

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
    public CompletableFuture<Dataset> generateMopacDescriptors(String pdbFile) {
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

        CompletableFuture<AmbitTask> result = datasetConsumer.createDatasetByPDB(file);
        return result
                .thenCompose((t) -> taskConsumer.waitTask(t.getId(), 5000))
                .thenCompose((t) -> {
                    String datasetURI = t.getResult();
                    Map<String, List<String>> parameters = new HashMap<>();
                    parameters.put("dataset_uri", Arrays.asList(datasetURI));
                    parameters.put("mopac_commands", Arrays.asList(MOPAC_COMMANDS));
                    return algorithmConsumer.train("ambit2.mopac.MopacOriginalStructure", parameters);
                })
                .thenCompose(t -> taskConsumer.waitTask(t.getId(), 5000))
                .thenCompose(t -> datasetConsumer.getDatasetById(t.getResult().split("dataset/")[1]));
    }

    @Override
    public CompletableFuture<String> createBundle(BundleData bundleData, String username) {
        String substanceOwner = bundleData.getSubstanceOwner();
        if (substanceOwner == null || substanceOwner.isEmpty()) {
            throw new AmbitClientException("Field substanceOwner cannot be empty.");
        }
        final String[] bundleUri = new String[1];
        final String[] bundle = new String[1];

        CompletableFuture<AmbitTask> result = bundleConsumer.createBundle(bundleData.getDescription(), username, substanceOwner);
        return result
                .thenCompose(t -> taskConsumer.waitTask(t.getId(), 5000))
                .thenCompose((t) -> {
                    bundleUri[0] = t.getResult();
                    bundle[0] = String.valueOf(bundleUri[0].split("bundle/")[1]);

                    System.out.println(bundleUri[0]);
                    List<String> substances = bundleData.getSubstances();
                    if (substances == null || substances.isEmpty())
                        return substanceOwnerResourceConsumer.getOwnerSubstances(bundleData.getSubstanceOwner());
                    return CompletableFuture.completedFuture(null);
                }).thenCompose((t) -> {
                    System.out.println(bundleUri[0]);
                    List<String> substances = bundleData.getSubstances() == null ? t : bundleData.getSubstances();
                    List<CompletableFuture<AmbitTask>> completableFutureList = new LinkedList<CompletableFuture<AmbitTask>>();
                    for (String substance : substances) {
                        System.out.println(substance);
                        completableFutureList.add(bundleConsumer.putSubstanceByBundleId(bundle[0], substance).thenCompose(s -> taskConsumer.waitTask(s.getId(), 5000)));
                    }
                    return CompletableFuture.allOf((completableFutureList.toArray(new CompletableFuture[completableFutureList.size()])))
                            .thenApply(v -> completableFutureList.stream()
                                    .map(CompletableFuture::join)
                            );
                }).thenCompose((t) -> {
                    System.out.println(bundleUri[0]);
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
                    List<CompletableFuture<AmbitTask>> completableFutureList = new LinkedList<CompletableFuture<AmbitTask>>();

                    for (String topCategory : properties.keySet()) {
                        List<String> subCategories = properties.get(topCategory);
                        for (String subCategory : subCategories) {
                            System.out.println(topCategory + " " + subCategory);
                            completableFutureList.add(bundleConsumer.putPropertyByBundleId(bundle[0], topCategory, subCategory).thenCompose(s -> taskConsumer.waitTask(s.getId(), 5000)));
                        }
                    }
                    return CompletableFuture.allOf((completableFutureList.toArray(new CompletableFuture[completableFutureList.size()])))
                            .thenApply(v -> completableFutureList.stream()
                                    .map(CompletableFuture::join)
                            );
                }).thenCompose(
                        t -> {
                            System.out.println(bundleUri[0]);
                            return CompletableFuture.completedFuture("Bundle succesffully created with id " + bundleUri[0]);
                        });

    }

    @Override
    public CompletableFuture<Dataset> getDataset(String datasetId) {
        return datasetConsumer.getDatasetById(datasetId);
    }

    @Override
    public CompletableFuture<Dataset> getDatasetStructures(String datasetId) {
        return datasetConsumer.getStructuresByDatasetId(datasetId);
    }

    @Override
    public CompletableFuture<BundleSubstances> getBundleSubstances(String bundleId) {
        return bundleConsumer.getSubstancesByBundleId(bundleId);

    }

    @Override
    public CompletableFuture<Studies> getSubstanceStudies(String substanceId) {
        return substanceConsumer.getStudiesBySubstanceId(substanceId);
    }

    @Override
    public CompletableFuture<BundleProperties> getBundleProperties(String bundleId) {
        return bundleConsumer.getPropertiesByBundleId(bundleId);
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

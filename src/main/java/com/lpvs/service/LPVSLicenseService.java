/**
 * Copyright (c) 2022, Samsung Electronics Co., Ltd. All rights reserved.
 *
 * Use of this source code is governed by a MIT license that can be
 * found in the LICENSE file.
 */

package com.lpvs.service;

import com.lpvs.entity.LPVSFile;
import com.lpvs.entity.LPVSLicense;
import com.lpvs.entity.LPVSLicenseConflict;
import com.lpvs.entity.LPVSQueue;
import com.lpvs.repository.LPVSLicenseConflictRepository;
import com.lpvs.repository.LPVSLicenseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import java.util.*;

@Service
@Slf4j
public class LPVSLicenseService {

    private final static String LICENSE_CONFLICT_SOURCE_PROP_NAME = "license_conflict";

    private final static String LICENSE_CONFLICT_SOURCE_ENV_VAR_NAME = "LPVS_LICENSE_CONFLICT";

    private final static String LICENSE_CONFLICT_SOURCE_DEFAULT = "db";

    public String licenseConflictsSource;

    private List<LPVSLicense> licenses;

    private List<Conflict<String, String>> licenseConflicts;

    @Autowired
    public LPVSLicenseService(@Value("${" + LICENSE_CONFLICT_SOURCE_PROP_NAME + ":" + LICENSE_CONFLICT_SOURCE_DEFAULT + "}") String licenseConflictsSource) {
        this.licenseConflictsSource = licenseConflictsSource;
    }

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    private LPVSLicenseRepository lpvsLicenseRepository;

    @Autowired
    private LPVSLicenseConflictRepository lpvsLicenseConflictRepository;

    @PostConstruct
    private void init() {
        licenseConflictsSource = (licenseConflictsSource == null || licenseConflictsSource.equals(
                LICENSE_CONFLICT_SOURCE_DEFAULT
        ))
                && System.getenv(LICENSE_CONFLICT_SOURCE_ENV_VAR_NAME) != null
                && !System.getenv(LICENSE_CONFLICT_SOURCE_ENV_VAR_NAME).isEmpty() ?
                System.getenv(LICENSE_CONFLICT_SOURCE_ENV_VAR_NAME) : licenseConflictsSource;

        if (licenseConflictsSource == null || licenseConflictsSource.isEmpty()) {
            log.error(LICENSE_CONFLICT_SOURCE_ENV_VAR_NAME + "(" + LICENSE_CONFLICT_SOURCE_PROP_NAME + ") is not set");
            System.exit(SpringApplication.exit(applicationContext, () -> -1));
        }
        try {
            // 1. Load licenses from DB
            licenses = lpvsLicenseRepository.takeAllLicenses();
            // print info
            log.info("LICENSES: loaded " + licenses.size() + " licenses from DB.");

            // 2. Load license conflicts
            licenseConflicts = new ArrayList<>();

            if (licenseConflictsSource.equalsIgnoreCase("db")) {
                List<LPVSLicenseConflict> conflicts = lpvsLicenseConflictRepository.takeAllLicenseConflicts();
                for (LPVSLicenseConflict conflict : conflicts) {
                    Conflict<String, String> conf = new Conflict<>(conflict.getConflictLicense().getSpdxId(), conflict.getRepositoryLicense().getSpdxId());
                    if (!licenseConflicts.contains(conf)) {
                        licenseConflicts.add(conf);
                    }
                }
                // print info
                log.info("LICENSE CONFLICTS: loaded " + licenseConflicts.size() + " license conflicts from DB.");
            }

        } catch (Exception ex) {
            log.warn("LICENSES and LICENSE CONFLICTS are not loaded.");
            log.error(ex.toString());
            licenses = new ArrayList<>();
            licenseConflicts = new ArrayList<>();
        }
    }

    public LPVSLicense findLicenseBySPDX(String name) {
        for (LPVSLicense license : licenses) {
            if (license.getSpdxId().equalsIgnoreCase(name)) {
                return license;
            }
        }
        return null;
    }

    public void addLicenseToList(LPVSLicense license) {
        licenses.add(license);
    }

    public LPVSLicense  findLicenseByName(String name) {
        for (LPVSLicense license : licenses) {
            if (license.getLicenseName().equalsIgnoreCase(name)) {
                return license;
            }
        }
        return null;
    }

    public void addLicenseConflict(String license1, String license2) {
        Conflict<String, String> conf = new Conflict<>(license1, license2);
        if (!licenseConflicts.contains(conf)) {
            licenseConflicts.add(conf);
        }
    }


    public LPVSLicense checkLicense(String spdxId) {
        LPVSLicense newLicense = findLicenseBySPDX(spdxId);
        if (newLicense == null && spdxId.contains("+")) {
            newLicense = findLicenseBySPDX(spdxId.replace("+", "") + "-or-later");
        }
        if (newLicense == null && spdxId.contains("+")) {
            newLicense = findLicenseBySPDX(spdxId.replace("+", "") + "-only");
        }
        return newLicense;
    }

    // Changed method to never return null
    public List<Conflict<String, String>> findConflicts(LPVSQueue webhookConfig, List<LPVSFile> scanResults) {
        List<Conflict<String, String>> foundConflicts = new ArrayList<>();

        if (scanResults.isEmpty() || licenseConflicts.isEmpty()) {
            return foundConflicts;
        }

        // 0. Extract the set of detected licenses from scan results
        List<String> detectedLicenses = new ArrayList<>();
        for (LPVSFile result : scanResults) {
            for (LPVSLicense license : result.getLicenses()) {
                detectedLicenses.add(license.getSpdxId());
            }
        }
        // leave license SPDX IDs without repetitions
        Set<String> detectedLicensesUnique = new HashSet<>(detectedLicenses);

        // 1. Check conflict between repository license and detected licenses
        String repositoryLicense = webhookConfig.getRepositoryLicense();
        // ToDo: add check for license alternative names. Reason: GitHub can use not SPDX ID.
        if (repositoryLicense != null) {
            for (String detectedLicenseUnique : detectedLicensesUnique) {
                for (Conflict<String, String> licenseConflict : licenseConflicts) {
                    Conflict<String, String> possibleConflict = new Conflict<>(detectedLicenseUnique, repositoryLicense);
                    if (licenseConflict.equals(possibleConflict)) {
                        foundConflicts.add(possibleConflict);
                    }
                }
            }
        }

        // 2. Check conflict between detected licenses
        for (int i = 0; i < detectedLicensesUnique.size(); i++) {
            for (int j = i + 1; j < detectedLicensesUnique.size(); j++) {
                for (Conflict<String, String> licenseConflict : licenseConflicts) {
                    Conflict<String, String> possibleConflict =
                            new Conflict<>(
                                    (String) detectedLicensesUnique.toArray()[i],
                                    (String) detectedLicensesUnique.toArray()[j]
                            );
                    if (licenseConflict.equals(possibleConflict)) {
                        foundConflicts.add(possibleConflict);
                    }
                }
            }
        }

        return foundConflicts;
    }

    public static class Conflict<License1, License2> {
        public License1 l1;
        public License2 l2;
        Conflict(License1 l1, License2 l2) {
            this.l1 = l1;
            this.l2 = l2;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Conflict<?, ?> conflict = (Conflict<?, ?>) o;
            return (l1.equals(conflict.l1) && l2.equals(conflict.l2)) ||
                    (l1.equals(conflict.l2) && l2.equals(conflict.l1));
        }

        @Override
        public int hashCode() {
            return Objects.hash(l1, l2);
        }
    }
}

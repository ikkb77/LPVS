/**
 * Copyright (c) 2022, Samsung Electronics Co., Ltd. All rights reserved.
 *
 * Use of this source code is governed by a MIT license that can be
 * found in the LICENSE file.
 */

package com.lpvs.util;

import com.lpvs.entity.LPVSDiffFile;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

@Slf4j
public class LPVSFileUtil {

    public static String saveFiles(Iterable<GHPullRequestFileDetail> files, String folder, String headCommitSHA, int deletions) {

        String directoryPath = "Projects/" + folder + "/" + headCommitSHA;
        String directoryDeletionPath = "";
        if (deletions > 0) {
            directoryDeletionPath = directoryPath + "delete";
        }

        try {
            deleteIfExists(directoryPath);
            boolean result = new File(directoryPath).mkdirs();
            boolean delResult = true;
            if (deletions > 0) {
                deleteIfExists(directoryDeletionPath);
                delResult = new File(directoryDeletionPath).mkdirs();
            }
            if (result && delResult) {
                for (GHPullRequestFileDetail file : files) {
                    String patch = file.getPatch();
                    if (patch == null) {
                        log.error("NULL PATCH for file "+ file.getFilename());
                        continue;
                    }
                    int cnt = 1;
                    StringBuilder prettyPatch = new StringBuilder();
                    StringBuilder prettyPatchDeletion = new StringBuilder();
                    for (String patchString : patch.split("\n")) {
                        // added line
                        if (patchString.charAt(0) == '+') {
                            prettyPatch.append(patchString.substring(patchString.indexOf("+") + 1));
                            prettyPatch.append("\n");
                            cnt++;
                        }
                        // removed line
                        else if (patchString.charAt(0) == '-') {
                            prettyPatchDeletion.append(patchString.substring(patchString.indexOf("-") + 1));
                            prettyPatchDeletion.append("\n");
                        }
                        // information(location, number of lines) about changed lines
                        else if (patchString.charAt(0) == '@') {
                            int fIndex = patchString.indexOf("+") + 1;
                            int lIndex = patchString.indexOf(',', fIndex);
                            if (lIndex == -1) lIndex = patchString.indexOf(' ', fIndex);
                            int startLine = Integer.parseInt(patchString.substring(fIndex, lIndex));
                            log.debug("Line from: " + startLine + " Git string: " + patchString);
                            for (int i = cnt; i < startLine; i++) {
                                prettyPatch.append("\n");
                            }
                            cnt=startLine;
                        }
                        // unchanged line
                        else if (patchString.charAt(0) == ' ') {
                            prettyPatch.append("\n");
                            cnt++;
                        }
                    }
                    String filename = file.getFilename();
                    if (filename.contains("/")) {
                        String filepath = filename.substring(0,filename.lastIndexOf("/"));
                        new File(directoryPath + "/" + filepath).mkdirs();
                        if (prettyPatchDeletion.length() > 0) {
                            new File(directoryDeletionPath+ "/" + filepath).mkdirs();
                        }
                    }

                    if (prettyPatch.length() > 0) {
                        BufferedWriter writer = new BufferedWriter(new FileWriter(directoryPath + "/" + filename));
                        writer.write(prettyPatch.toString());
                        writer.close();
                    }

                    if (prettyPatchDeletion.length() > 0) {
                        BufferedWriter writer = new BufferedWriter(new FileWriter(directoryDeletionPath + "/" + filename));
                        writer.write(prettyPatchDeletion.toString());
                        writer.close();
                    }
                }
            }
        } catch (IOException e) {
            log.error("Error while writing file. " + e.getMessage());
        }

        if (deletions > 0) {
            return directoryPath + ":::::" + directoryDeletionPath;
        } else {
            return directoryPath;
        }
    }

    public static void deleteIfExists (String path) {
        File dir =  new File(path);
        if (dir.exists()) {
            FileSystemUtils.deleteRecursively(dir);
        }
    }

    public static List<LPVSDiffFile> parseDiff(String diffString){
        LinkedList<LPVSDiffFile> resultFiles = new LinkedList<>();
        String[] lines = diffString.split("\n");
        log.info("# of lines: " + lines.length);
        int count = 0;
        for (String line: lines){
            count += 1;
            if (line.startsWith("+++") || line.startsWith("---")){
                if (line.startsWith("---")){
                    log.debug("line.startsWith(\"---\")");
                    LPVSDiffFile diffFile = new LPVSDiffFile();
                    diffFile.setOriginalFile(line.replace("--- ", "")
                            .replace("---", "")
                            .replace("a/", "")
                            .replace("b/", ""));
                    resultFiles.add(diffFile);
                }
                if (line.startsWith("+++")){
                    log.debug("line.startsWith(\"+++\")");
                    resultFiles.getLast().setNewFile(line.replace("+++ ", "")
                            .replace("+++", "")
                            .replace("a/", "")
                            .replace("b/", ""));
                }
            } else if (line.startsWith("+")){
                log.debug("line.startsWith(\"+\")");
                resultFiles.getLast().appendAddedLine(line.replace("+", ""));
            } else if (line.startsWith("-")){
                log.debug("line.startsWith(\"-\")");
                resultFiles.getLast().appendDeletedLine(line.replace("-", ""));
            } else {
                log.debug("Nothing!");
            }
        }
        return resultFiles;
    }

    public static LPVSDiffFile checkFilePath(LPVSDiffFile diffFile){
        if (!diffFile.getNewFile().equals(diffFile.getOriginalFile())){
            if (diffFile.getNewFile().contains("/dev/null") && !diffFile.getOriginalFile().contains("/dev/null"))
                diffFile.setNewFile(diffFile.getOriginalFile());
            if (diffFile.getOriginalFile().contains("/dev/null") && !diffFile.getNewFile().contains("/dev/null"))
                diffFile.setOriginalFile(diffFile.getNewFile());
        }
        return diffFile;
    }
}


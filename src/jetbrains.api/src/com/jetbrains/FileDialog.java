/*
 * Copyright 2000-2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jetbrains;

public interface FileDialog {

    static FileDialog get(java.awt.FileDialog dialog) {
        if (FileDialogService.INSTANCE == null) return null;
        else return FileDialogService.INSTANCE.getFileDialog(dialog);
    }

    void setSelectionMode(boolean directories, boolean files);

    void setCanCreateDirectories(boolean value);

    void setLocalizationStrings(String openButtonText, String selectFolderButtonText);
}

interface FileDialogService {
    FileDialogService INSTANCE = JBR.api.getService(FileDialogService.class);
    FileDialog getFileDialog(java.awt.FileDialog dialog);
}

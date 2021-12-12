package com.jetbrains.desktop;

import java.io.Serial;
import java.io.Serializable;
import java.lang.annotation.Native;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class FileDialog implements Serializable {

    @Serial
    private static final long serialVersionUID = -8889549523802843037L;

    private static final VarHandle getter;
    static {
        try {
            getter = MethodHandles.privateLookupIn(java.awt.FileDialog.class, MethodHandles.lookup())
                    .findVarHandle(java.awt.FileDialog.class, "jbrDialog", FileDialog.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new Error(e);
        }
    }
    public static FileDialog get(java.awt.FileDialog dialog) {
        FileDialog result = (FileDialog) getter.get(dialog);
        if (result == null) getter.set(dialog, result = new FileDialog());
        return result;
    }

    @Native public static final int SELECT_DEFAULT = 0, SELECT_FILES_ONLY = 1,
            SELECT_DIRECTORIES_ONLY = 2, SELECT_FILES_AND_DIRECTORIES = 3;

    /**
     * Whether to select files, directories or both (used when common file dialogs are enabled on Windows, or on macOS)
     */
    public int selectionMode = SELECT_DEFAULT;

    /**
     * Whether to allow creating directories or not (used on macOS)
     */
    public Boolean canCreateDirectories;

    /**
     * Text for "Open" button (used when common file dialogs are enabled on
     * Windows).
     */
    public String openButtonText;

    /**
     * Text for "Select Folder" button (used when common file dialogs are
     * enabled on Windows).
     */
    public String selectFolderButtonText;

    public void setSelectionMode(boolean directories, boolean files) {
        if (directories) {
            if (files) selectionMode = SELECT_FILES_AND_DIRECTORIES;
            else selectionMode = SELECT_DIRECTORIES_ONLY;
        } else {
            if (files) selectionMode = SELECT_FILES_ONLY;
            else throw new IllegalArgumentException("(directories || files) == false");
        }
    }

    public void setCanCreateDirectories(boolean value) {
        this.canCreateDirectories = value;
    }

    public void setLocalizationStrings(String openButtonText, String selectFolderButtonText) {
        this.openButtonText = openButtonText;
        this.selectFolderButtonText = selectFolderButtonText;
    }

}

/*
 * Copyright 2012-2016, Wladimir Leite, Luis Filipe Nassif
 * 
 * This file is part of Indexador e Processador de Evidências Digitais (IPED).
 *
 * IPED is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * IPED is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with IPED.  If not, see <http://www.gnu.org/licenses/>.
 */
package dpf.sp.gpinf.indexer.process.task;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.tika.mime.MediaType;

import dpf.sp.gpinf.carver.api.CarverConfiguration;
import dpf.sp.gpinf.indexer.datasource.SleuthkitReader;
import dpf.sp.gpinf.indexer.process.Worker.ProcessTime;
import gpinf.dev.data.Item;
import iped3.IItem;
import iped3.sleuthkit.ISleuthKitItem;

/**
 * Classe base de tarefas de carving. Centraliza contador de itens carveados e
 * outros métodos comuns.
 */
public abstract class BaseCarveTask extends AbstractTask {

    public static MediaType UNALLOCATED_MIMETYPE = MediaType.parse("application/x-unallocated"); //$NON-NLS-1$
    protected static MediaType mtPageFile = MediaType.application("x-pagefile"); //$NON-NLS-1$
    protected static MediaType mtVolumeShadow = MediaType.application("x-volume-shadow"); //$NON-NLS-1$
    protected static MediaType mtDiskImage = MediaType.application("x-disk-image"); //$NON-NLS-1$
    protected static MediaType mtVmdk = MediaType.application("x-vmdk"); //$NON-NLS-1$
    protected static MediaType mtVhd = MediaType.application("x-vhd"); //$NON-NLS-1$
    protected static MediaType mtVdi = MediaType.application("x-vdi"); //$NON-NLS-1$
    protected static MediaType mtUnknown = MediaType.application("octet-stream"); //$NON-NLS-1$

    public static final String FILE_FRAGMENT = "fileFragment"; //$NON-NLS-1$

    protected static CarverConfiguration carverConfig = null;

    private static int itensCarved;

    private Set<Long> kffCarvedOffsets;
    private IItem prevEvidence;

    protected static final Map<IItem, Set<Long>> kffCarved = new HashMap<IItem, Set<Long>>();

    private final synchronized static void incItensCarved() {
        itensCarved++;
    }

    public final synchronized static int getItensCarved() {
        return itensCarved;
    }

    protected void addFragmentFile(IItem parentEvidence, long off, long len, int fragNum) {
        String name = parentEvidence.getName() + "_" + fragNum; //$NON-NLS-1$
        Item fragFile = getOffsetFile(parentEvidence, off, len, name, parentEvidence.getMediaType());
        configureOffsetItem(parentEvidence, fragFile, off);
        fragFile.setExtension(parentEvidence.getExt());
        fragFile.setAccessDate(parentEvidence.getAccessDate());
        if (parentEvidence.getExtraAttribute(SleuthkitReader.IN_FAT_FS) != null)
            fragFile.setExtraAttribute(SleuthkitReader.IN_FAT_FS, true);
        fragFile.setCreationDate(parentEvidence.getCreationDate());
        fragFile.setModificationDate(parentEvidence.getModDate());
        fragFile.setRecordDate(parentEvidence.getRecordDate());
        fragFile.setExtraAttribute(FILE_FRAGMENT, true);
        addOffsetFile(fragFile, parentEvidence);
    }

    protected IItem addCarvedFile(IItem parentEvidence, long off, long len, String name, MediaType mediaType) {
        IItem carvedEvidence = createCarvedFile(parentEvidence, off, len, name, mediaType);
        if (carvedEvidence != null)
            addOffsetFile(carvedEvidence, parentEvidence);
        return carvedEvidence;
    }

    protected IItem createCarvedFile(IItem parentEvidence, long off, long len, String name, MediaType mediaType) {

        if (kffCarvedExists(parentEvidence, off))
            return null;

        Item carvedEvidence = getOffsetFile(parentEvidence, off, len, name, mediaType);
        carvedEvidence.setCarved(true);
        configureOffsetItem(parentEvidence, carvedEvidence, off);

        return carvedEvidence;
    }

    protected Item getOffsetFile(IItem parentEvidence, long off, long len, String name, MediaType mediaType) {
        Item offsetFile = new Item();
        offsetFile.setName(name);
        offsetFile.setPath(parentEvidence.getPath() + ">>" + name); //$NON-NLS-1$
        len = Math.min(len, parentEvidence.getLength() - off);
        offsetFile.setLength(len);
        offsetFile.setSumVolume(false);
        offsetFile.setParent(parentEvidence);

        offsetFile.setDeleted(parentEvidence.isDeleted());

        if (mediaType != null)
            offsetFile.setMediaType(mediaType);

        long prevOff = parentEvidence.getFileOffset();
        offsetFile.setFileOffset(prevOff == -1 ? off : prevOff + off);

        return offsetFile;
    }

    protected void addOffsetFile(IItem offsetFile, IItem parentEvidence) {
        // Caso o item pai seja um subitem a ser excluído pelo filtro de exportação,
        // processa no worker atual
        boolean processNow = parentEvidence.isSubItem() && !parentEvidence.isToAddToCase();
        ProcessTime time = processNow ? ProcessTime.NOW : ProcessTime.AUTO;
        if (offsetFile.isCarved()) {
            incItensCarved();
        }
        worker.processNewItem(offsetFile, time);
    }

    protected boolean isToProcess(IItem evidence) {
        if (evidence.isCarved() || evidence.getExtraAttribute(BaseCarveTask.FILE_FRAGMENT) != null
                || !carverConfig.isToProcess(evidence.getMediaType())) {
            return false;
        }
        return true;
    }

    private boolean kffCarvedExists(IItem parentEvidence, long off) {
        if (!parentEvidence.equals(prevEvidence)) {
            synchronized (kffCarved) {
                kffCarvedOffsets = kffCarved.get(parentEvidence);
            }
            prevEvidence = parentEvidence;
        }
        if (kffCarvedOffsets != null && kffCarvedOffsets.contains(off)) {
            return true;
        } else
            return false;
    }

    private void configureOffsetItem(IItem parentItem, Item carvedItem, long offset) {
        if (parentItem.getIdInDataSource() != null) {
            carvedItem.setIdInDataSource(parentItem.getIdInDataSource());
            carvedItem.setInputStreamFactory(parentItem.getInputStreamFactory());

        } else if (parentItem instanceof ISleuthKitItem && ((ISleuthKitItem) parentItem).getSleuthFile() != null) {
            carvedItem.setSleuthFile(((ISleuthKitItem) parentItem).getSleuthFile());
            carvedItem.setSleuthId(((ISleuthKitItem) parentItem).getSleuthId());

        } else {
            carvedItem.setFile(parentItem.getFile());
            carvedItem.setExportedFile(parentItem.getExportedFile());
        }
        // optimization to not create more temp files
        if (parentItem.hasTmpFile()) {
            try {
                carvedItem.setFile(parentItem.getTempFile());
                carvedItem.setTempStartOffset(offset);
            } catch (IOException e) {
                // ignore
            }
        }
        parentItem.setHasChildren(true);
    }

    // adiciona uma evidência já carveada por uma classe que implemente a interface
    // Carver
    protected boolean addCarvedEvidence(Item parentEvidence, Item carvedEvidence, long off) {

        if (kffCarvedExists(parentEvidence, off))
            return false;

        configureOffsetItem(parentEvidence, carvedEvidence, off);
        carvedEvidence.setCarved(true);

        addOffsetFile(carvedEvidence, parentEvidence);

        return true;
    }
}
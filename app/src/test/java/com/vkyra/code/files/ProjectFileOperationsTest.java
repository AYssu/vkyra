package com.vkyra.code.files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class ProjectFileOperationsTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    @Test public void basenameRejectsTraversalSeparatorsAndControlCharacters() {
        for(String invalid:new String[]{"",".","..","../other","one/two","one\\two","/root","bad\nname","zero\u0000name"})
            assertFalse(invalid,ProjectFileOperations.isBasename(invalid));
        for(String valid:new String[]{"main.cpp","中文 😀.cpp",".hidden","two..dots"})
            assertTrue(valid,ProjectFileOperations.isBasename(valid));
    }

    @Test public void renamePreservesBytesAndStaysInOriginalParent() throws Exception {
        File source=temporary.newFile("old.cpp");Files.write(source.toPath(),new byte[]{2,4,6});
        File result=ProjectFileOperations.rename(source,"new.cpp");
        assertEquals(new File(temporary.getRoot(),"new.cpp"),result);
        assertFalse(source.exists());assertArrayEquals(new byte[]{2,4,6},Files.readAllBytes(result.toPath()));
    }

    @Test public void existingDestinationAndInvalidNameNeverChangeEitherEntry() throws Exception {
        File source=temporary.newFile("source.cpp"),destination=temporary.newFile("exists.cpp");
        Files.write(source.toPath(),new byte[]{1});Files.write(destination.toPath(),new byte[]{9});
        for(String name:new String[]{"exists.cpp","../escape.cpp","..","bad\\path"}) {
            try { ProjectFileOperations.rename(source,name);fail(name); } catch(IOException expected) { }
            assertArrayEquals(new byte[]{1},Files.readAllBytes(source.toPath()));
            assertArrayEquals(new byte[]{9},Files.readAllBytes(destination.toPath()));
        }
    }

    @Test public void danglingDestinationSymlinkAlsoPreventsReplacement() throws Exception {
        File source=temporary.newFile("source"),destination=new File(temporary.getRoot(),"exists");
        Files.createSymbolicLink(destination.toPath(),new File(temporary.getRoot(),"absent").toPath());
        try { ProjectFileOperations.rename(source,"exists");fail("dangling destination must not be overwritten"); }
        catch(java.nio.file.FileAlreadyExistsException expected) { }
        assertTrue(source.exists());assertTrue(Files.isSymbolicLink(destination.toPath()));
    }

    @Test public void missingSourceFailsWithoutCreatingDestination() throws Exception {
        try { ProjectFileOperations.rename(new File(temporary.getRoot(),"missing"),"new");fail(); }
        catch(IOException expected) { }
        assertFalse(new File(temporary.getRoot(),"new").exists());
    }

    @Test public void recursiveDeleteDoesNotFollowDirectoryOrDanglingSymlinks() throws Exception {
        File outside=temporary.newFolder("outside");File sentinel=new File(outside,"sentinel");
        Files.write(sentinel.toPath(),new byte[]{7,5,3});
        File selected=temporary.newFolder("selected"),nested=new File(selected,"nested");assertTrue(nested.mkdir());
        Files.write(new File(nested,"owned").toPath(),new byte[]{1});
        Files.createSymbolicLink(new File(nested,"escape").toPath(),outside.toPath());
        Files.createSymbolicLink(new File(selected,"dangling").toPath(),new File(outside,"absent").toPath());
        ProjectFileOperations.deleteRecursively(selected);
        assertFalse(Files.exists(selected.toPath(),LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(new byte[]{7,5,3},Files.readAllBytes(sentinel.toPath()));
    }

    @Test public void deletingSelectedSymlinkRemovesOnlyTheLink() throws Exception {
        File outside=temporary.newFolder("target");File sentinel=new File(outside,"sentinel");
        Files.write(sentinel.toPath(),new byte[]{8});
        File link=new File(temporary.getRoot(),"link");Files.createSymbolicLink(link.toPath(),outside.toPath());
        ProjectFileOperations.deleteRecursively(link);
        assertFalse(Files.exists(link.toPath(),LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(new byte[]{8},Files.readAllBytes(sentinel.toPath()));
    }

    @Test public void openedDescendantAndCanonicalAliasAreProtectedWithoutPrefixFalsePositives() throws Exception {
        File selected=temporary.newFolder("dir"),file=new File(selected,"opened.cpp");Files.write(file.toPath(),new byte[]{1});
        assertTrue(ProjectFileOperations.contains(selected,file));
        assertFalse(ProjectFileOperations.contains(selected,new File(temporary.getRoot(),"dir-other/opened.cpp")));
        File alias=new File(temporary.getRoot(),"alias");Files.createSymbolicLink(alias.toPath(),selected.toPath());
        for(File opened:Arrays.asList(file,new File(alias,"opened.cpp"))) {
            try { ProjectFileOperations.requireUnprotected(selected,Collections.singletonList(opened));fail(); }
            catch(IOException expected) { assertTrue(expected.getMessage().contains("关闭")); }
        }
        ProjectFileOperations.requireUnprotected(selected,Collections.singletonList(new File(temporary.getRoot(),"dir-other")));
    }

    @Test public void fileSystemRootCannotBeRenamedOrDeleted() throws Exception {
        try { ProjectFileOperations.rename(new File("/"),"renamed");fail(); } catch(IOException expected) { }
        try { ProjectFileOperations.deleteRecursively(new File("/"));fail(); } catch(IOException expected) { }
    }
}

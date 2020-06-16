package io.nanovc.nano_jgit_analyzer;

import io.nanovc.areas.ByteArrayHashMapArea;
import io.nanovc.junit.TestDirectory;
import io.nanovc.junit.TestDirectoryExtension;
import io.nanovc.memory.MemoryCommit;
import io.nanovc.memory.MemoryNanoRepo;
import io.nanovc.memory.MemorySearchResults;
import io.nanovc.searches.commits.SimpleSearchQueryDefinition;
import io.nanovc.searches.commits.expressions.AllRepoCommitsExpression;
import io.nanovc.searches.commits.expressions.TipOfExpression;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@ExtendWith(TestDirectoryExtension.class)
public class AnalysisTests
{
    /**
     * Tests git analysis.
     *
     * @param testPath The path to a temporary folder for this test.
     */
    @Test
    public void testAnalysis(@TestDirectory(useTestName = true) Path testPath) throws GitAPIException, IOException
    {
        // Create the nano repo where we will load the entire Git repo:
        MemoryNanoRepo nanoRepo = new MemoryNanoRepo();

        try (
            Git git = Git.cloneRepository()
                .setURI("https://github.com/nanovc/nano-jgit-analyzer.git")
                .setDirectory(testPath.toFile())
                .call()
        )
        {
            // Get the low level repository so we can interrogate it directly:
            Repository repository = git.getRepository();

            //
            //
            //
            // // Get all the commits for the git repo:
            // LogCommand logCommand = git
            //     .log()
            //     .all();
            // Iterable<RevCommit> revCommits = logCommand.call();


            try (RevWalk revWalk = new RevWalk(repository))
            {
                // Get the object reader that we are using for this revision walk:
                ObjectReader objectReader = revWalk.getObjectReader();

                // Get all the references for this repository:
                List<Ref> allRefs = repository.getRefDatabase().getRefs();

                // Go through each references and make sure that we walk it:
                for (Ref ref : allRefs)
                {
                    // Check whether the reference is peeled (annotated tags).
                    if(!ref.isPeeled())
                    {
                        // The reference is not peeled.
                        // Peel the reference:
                        ref = repository.getRefDatabase().peel(ref);
                    }

                    // Get the objectID for this reference:
                    ObjectId objectId = ref.getPeeledObjectId();
                    if (objectId == null)
                        objectId = ref.getObjectId();

                    // Try to get the revision commit:
                    RevCommit revCommit = null;
                    try
                    {
                        // Parse the commit information:
                        revCommit = revWalk.parseCommit(objectId);
                    }
                    catch (MissingObjectException | IncorrectObjectTypeException e)
                    {
                        // ignore as traversal starting point:
                        // - the ref points to an object that does not exist
                        // - the ref points to an object that is not a commit (e.g. a
                        // tree or a blob)
                    }
                    if (revCommit != null)
                    {
                        // Add this commit as a starting point for our revision walk:
                        revWalk.markStart(revCommit);
                    }
                }
                // Now we have all the references marked as starting points for the revision walk.

                // Define the sort order that we want:
                // We want all parents to be traversed (old commits) before children and then children must be traversed in time order.
                revWalk.sort(RevSort.TOPO, true);
                revWalk.sort(RevSort.COMMIT_TIME_DESC, true);
                revWalk.sort(RevSort.REVERSE, true);

                // Walk each revision:
                for (RevCommit revCommit : revWalk)
                {
                    System.out.println("id: " + revCommit.getId());
                    System.out.println("name: " + revCommit.getName());
                    System.out.println("message: " + revCommit.getFullMessage());

                    // Create a content area for this commit:
                    ByteArrayHashMapArea contentArea = nanoRepo.createArea();

                    // Get the tree of files for this commit:
                    RevTree commitRootOfTree = revCommit.getTree();

                    // Walk the tree for this revision:
                    try(TreeWalk treeWalk = new TreeWalk(repository, objectReader))
                    {
                        // Walk the tree recursively:
                        treeWalk.setRecursive(true);

                        // Set the root of the tree walker to point at the root of the commit:
                        treeWalk.addTree(commitRootOfTree);

                        // Get all the paths for the commit by walking the tree:
                        while (treeWalk.next())
                        {
                            // Get the details of this path:
                            final ObjectId blobId = treeWalk.getObjectId(0);
                            final FileMode mode = treeWalk.getFileMode(0);
                            final String path = treeWalk.getPathString();
                            // System.out.println(path + ":" + blobId.getName());

                            // Get the loader for the contents of the file:
                            ObjectLoader blobLoader = repository.open(blobId);

                            // Get the bytes for the blob:
                            byte[] blobBytes = blobLoader.getBytes();
                            //System.out.println(blobBytes.length);
                            //System.out.println(new String(blobBytes));

                            // Place the bytes in our content area:
                            contentArea.putBytes(path, blobBytes);
                        }
                    }
                    // Now we have walked the entire tree of paths for this commit.

                    // Commit the content area to nano version control:
                    MemoryCommit memoryCommit = nanoRepo.commit(contentArea, revCommit.getFullMessage());

                    // Checkout the revision:
                    // System.out.println("Checking out...");
                    // git
                    //     .checkout()
                    //     .setStartPoint(revCommit)
                    //     .setAllPaths(true)
                    //     .call();
                    // System.out.println("Checking out done!");

                }
            }

        }

        // Search the repo:
        System.out.println("MEMORY REPO: Tip");
        MemorySearchResults search = nanoRepo.search(new SimpleSearchQueryDefinition(new TipOfExpression(new AllRepoCommitsExpression()), null, null));
        for (MemoryCommit commit : search.getCommits())
        {
            ByteArrayHashMapArea checkout = nanoRepo.checkout(commit);
            System.out.println(checkout.asListString());
        }
    }

    static class Commit
    {
        public String id;

        public long timestamp;

        /**
         * A repository slug is a URL-friendly version of a repository name,
         * automatically generated by Bitbucket for use in the URL.
         * For example, if your repository name was 'føøbar', in the URL it would become 'foobar'.
         * Similarly, 'foo bar' would become 'foo-bar'.
         * https://confluence.atlassian.com/bitbucket/what-is-a-slug-224395839.html
         */
        public String slug;

        public String author_email;

        public String author_name;

        public String message;
    }
}

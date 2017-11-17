package org.spigotmc.builder;

import com.google.common.base.Charsets;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.collect.ObjectArrays;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import com.google.gson.Gson;
import difflib.DiffUtils;
import difflib.Patch;
import java.awt.Desktop;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.swing.JFrame;
import javax.swing.JLabel;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.TeeOutputStream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;

public class Builder
{

    public static final String LOG_FILE = "BuildTools.log.txt";
    public static final boolean IS_WINDOWS = System.getProperty( "os.name" ).startsWith( "Windows" );
    public static final File CWD = new File( "." );
    private static final boolean autocrlf = !"\n".equals( System.getProperty( "line.separator" ) );
    private static boolean dontUpdate;
    private static boolean skipCompile;
    private static boolean generateSource;
    private static boolean generateDocs;
    private static boolean dev;
    private static String applyPatchesShell = "sh";
    //
    private static File msysDir;

    public static void main(String[] args) throws Exception
    {
        if ( CWD.getAbsolutePath().contains( "'" ) || CWD.getAbsolutePath().contains( "#" ) )
        {
            System.err.println( "Please do not run in a path with special characters!" );
            return;
        }

        if ( false && System.console() == null )
        {
            JFrame jFrame = new JFrame();
            jFrame.setTitle( "SpigotMC - BuildTools" );
            jFrame.setDefaultCloseOperation( JFrame.EXIT_ON_CLOSE );
            jFrame.getContentPane().add( new JLabel( "You have to run BuildTools through bash (msysgit). Please read our wiki." ) );
            jFrame.pack();
            jFrame.setVisible( true );

            Desktop.getDesktop().browse( new URI( "https://www.spigotmc.org/wiki/buildtools/" ) );
            return;
        }

        // May be null
        String buildVersion = Builder.class.getPackage().getImplementationVersion();
        int buildNumber = -1;
        if ( buildVersion != null )
        {
            String[] split = buildVersion.split( "-" );
            if ( split.length == 4 )
            {
                try
                {
                    buildNumber = Integer.parseInt( split[3] );
                } catch ( NumberFormatException ex )
                {
                }
            }
        }
        System.out.println( "Loading BuildTools version: " + buildVersion + " (#" + buildNumber + ")" );

        OptionParser parser = new OptionParser();
        OptionSpec<Void> disableCertFlag = parser.accepts( "disable-certificate-check" );
        OptionSpec<Void> dontUpdateFlag = parser.accepts( "dont-update" );
        OptionSpec<Void> skipCompileFlag = parser.accepts( "skip-compile" );
        OptionSpec<Void> generateSourceFlag = parser.accepts( "generate-source" );
        OptionSpec<Void> generateDocsFlag = parser.accepts( "generate-docs" );
        OptionSpec<Void> devFlag = parser.accepts( "dev" );
        OptionSpec<File> outputDir = parser.acceptsAll( Arrays.asList( "o", "output-dir" ) ).withRequiredArg().ofType( File.class ).defaultsTo( CWD );
        OptionSpec<String> jenkinsVersion = parser.accepts( "rev" ).withRequiredArg().defaultsTo( "latest" );

        OptionSet options = parser.parse( args );

        if ( options.has( disableCertFlag ) )
        {
            disableHttpsCertificateCheck();
        }
        dontUpdate = options.has( dontUpdateFlag );
        skipCompile = options.has( skipCompileFlag );
        generateSource = options.has( generateSourceFlag );
        generateDocs = options.has( generateDocsFlag );
        dev = options.has( devFlag );

        logOutput();

        try
        {
            runProcess( CWD, "sh", "-c", "exit" );
        } catch ( Exception ex )
        {
            if ( IS_WINDOWS )
            {
                String gitVersion = "PortableGit-2.15.0-" + ( System.getProperty( "os.arch" ).endsWith( "64" ) ? "64" : "32" ) + "-bit";
                msysDir = new File( gitVersion, "PortableGit" );

                if ( !msysDir.isDirectory() )
                {
                    System.out.println( "*** Could not find PortableGit installation, downloading. ***" );

                    String gitName = gitVersion + ".7z.exe";
                    File gitInstall = new File( gitVersion, gitName );
                    gitInstall.getParentFile().mkdirs();

                    if ( !gitInstall.exists() )
                    {
                        download( "https://static.spigotmc.org/git/" + gitName, gitInstall );
                    }

                    System.out.println( "Extracting downloaded git install" );
                    // yes to all, silent, don't run. Only -y seems to work
                    runProcess( gitInstall.getParentFile(), gitInstall.getAbsolutePath(), "-y", "-gm2", "-nr" );

                    gitInstall.delete();
                }

                System.out.println( "*** Using downloaded git " + msysDir + " ***" );
                System.out.println( "*** Please note that this is a beta feature, so if it does not work please also try a manual install of git from https://git-for-windows.github.io/ ***" );
            } else
            {
                System.out.println( "You must run this jar through bash (msysgit)" );
                System.exit( 1 );
            }
        }

        try
        {
            runProcess( CWD, "git", "config", "--global", "--includes", "user.name" );
        } catch ( Exception ex )
        {
            System.out.println( "Git name not set, setting it to default value." );
            runProcess( CWD, "git", "config", "--global", "user.name", "BuildTools" );
        }
        try
        {
            runProcess( CWD, "git", "config", "--global", "--includes", "user.email" );
        } catch ( Exception ex )
        {
            System.out.println( "Git email not set, setting it to default value." );
            runProcess( CWD, "git", "config", "--global", "user.email", "unconfigured@null.spigotmc.org" );
        }

        File workDir = new File( "work" );
        workDir.mkdir();

        File bukkit = new File( "Bukkit" );
        if ( !bukkit.exists() )
        {
            clone( "https://hub.spigotmc.org/stash/scm/spigot/bukkit.git", bukkit );
        }

        File craftBukkit = new File( "CraftBukkit" );
        if ( !craftBukkit.exists() )
        {
            clone( "https://hub.spigotmc.org/stash/scm/spigot/craftbukkit.git", craftBukkit );
        }

        File spigot = new File( "Spigot" );
        if ( !spigot.exists() )
        {
            clone( "https://hub.spigotmc.org/stash/scm/spigot/spigot.git", spigot );
        }

        File buildData = new File( "BuildData" );
        if ( !buildData.exists() )
        {
            clone( "https://hub.spigotmc.org/stash/scm/spigot/builddata.git", buildData );
        }

        File maven;
        String m2Home = System.getenv( "M2_HOME" );
        if ( m2Home == null || !( maven = new File( m2Home ) ).exists() )
        {
            maven = new File( "apache-maven-3.5.0" );

            if ( !maven.exists() )
            {
                System.out.println( "Maven does not exist, downloading. Please wait." );

                File mvnTemp = new File( "mvn.zip" );
                mvnTemp.deleteOnExit();

                download( "https://static.spigotmc.org/maven/apache-maven-3.5.0-bin.zip", mvnTemp );
                unzip( mvnTemp, new File( "." ) );
            }
        }

        String mvn = maven.getAbsolutePath() + "/bin/mvn";

        Git bukkitGit = Git.open( bukkit );
        Git craftBukkitGit = Git.open( craftBukkit );
        Git spigotGit = Git.open( spigot );
        Git buildGit = Git.open( buildData );

        BuildInfo buildInfo = new BuildInfo( "Dev Build", "Development", 0, new BuildInfo.Refs( "master", "master", "master", "master" ) );

        if ( !dontUpdate )
        {
            if ( !dev )
            {
                String askedVersion = options.valueOf( jenkinsVersion );
                System.out.println( "Attempting to build version: '" + askedVersion + "' use --rev <version> to override" );

                String verInfo;
                try
                {
                    verInfo = get( "https://hub.spigotmc.org/versions/" + askedVersion + ".json" );
                } catch ( IOException ex )
                {
                    System.err.println( "Could not get version " + askedVersion + " does it exist? Try another version or use 'latest'" );
                    ex.printStackTrace();
                    return;
                }
                System.out.println( "Found version" );
                System.out.println( verInfo );

                buildInfo = new Gson().fromJson( verInfo, BuildInfo.class );

                if ( buildNumber != -1 && buildInfo.getToolsVersion() != -1 && buildNumber < buildInfo.getToolsVersion() )
                {
                    System.err.println( "**** Your BuildTools is out of date and will not build the requested version. Please grab a new copy from https://www.spigotmc.org/go/buildtools-dl" );
                    System.exit( 1 );
                }
            }

            pull( buildGit, buildInfo.getRefs().getBuildData() );
            pull( bukkitGit, buildInfo.getRefs().getBukkit() );
            pull( craftBukkitGit, buildInfo.getRefs().getCraftBukkit() );
            pull( spigotGit, buildInfo.getRefs().getSpigot() );
        }

        VersionInfo versionInfo = new Gson().fromJson(
                Files.toString( new File( "BuildData/info.json" ), Charsets.UTF_8 ),
                VersionInfo.class
        );
        // Default to 1.8 builds.
        if ( versionInfo == null )
        {
            versionInfo = new VersionInfo( "1.8", "bukkit-1.8.at", "bukkit-1.8-cl.csrg", "bukkit-1.8-members.csrg", "package.srg", null );
        }
        System.out.println( "Attempting to build Minecraft with details: " + versionInfo );

        File vanillaJar = new File( workDir, "minecraft_server." + versionInfo.getMinecraftVersion() + ".jar" );
        if ( !vanillaJar.exists() || !checkHash( vanillaJar, versionInfo ) )
        {
            if ( versionInfo.getServerUrl() != null )
            {
                download( versionInfo.getServerUrl(), vanillaJar );
            } else
            {
                download( String.format( "https://s3.amazonaws.com/Minecraft.Download/versions/%1$s/minecraft_server.%1$s.jar", versionInfo.getMinecraftVersion() ), vanillaJar );

                // Legacy versions can also specify a specific shell to build with which has to be bash-compatible
                applyPatchesShell = System.getenv().get( "SHELL" );
                if ( applyPatchesShell == null || applyPatchesShell.trim().isEmpty() )
                {
                    applyPatchesShell = "bash";
                }
            }
        }
        if ( !checkHash( vanillaJar, versionInfo ) )
        {
            System.err.println( "**** Could not download clean Minecraft jar, giving up." );
            return;
        }

        Iterable<RevCommit> mappings = buildGit.log()
                .addPath( "mappings/" + versionInfo.getAccessTransforms() )
                .addPath( "mappings/" + versionInfo.getClassMappings() )
                .addPath( "mappings/" + versionInfo.getMemberMappings() )
                .addPath( "mappings/" + versionInfo.getPackageMappings() )
                .setMaxCount( 1 ).call();

        Hasher mappingsHash = Hashing.md5().newHasher();
        for ( RevCommit rev : mappings )
        {
            mappingsHash.putString( rev.getName(), Charsets.UTF_8 );
        }
        String mappingsVersion = mappingsHash.hash().toString().substring( 24 ); // Last 8 chars

        File finalMappedJar = new File( workDir, "mapped." + mappingsVersion + ".jar" );
        if ( !finalMappedJar.exists() )
        {
            System.out.println( "Final mapped jar: " + finalMappedJar + " does not exist, creating!" );

            File clMappedJar = new File( finalMappedJar + "-cl" );
            File mMappedJar = new File( finalMappedJar + "-m" );

            runProcess( CWD, "java", "-jar", "BuildData/bin/SpecialSource-2.jar", "map", "-i", vanillaJar.getPath(), "-m", "BuildData/mappings/" + versionInfo.getClassMappings(), "-o", clMappedJar.getPath() );

            runProcess( CWD, "java", "-jar", "BuildData/bin/SpecialSource-2.jar", "map", "-i", clMappedJar.getPath(),
                    "-m", "BuildData/mappings/" + versionInfo.getMemberMappings(), "-o", mMappedJar.getPath() );

            runProcess( CWD, "java", "-jar", "BuildData/bin/SpecialSource.jar", "--kill-lvt", "-i", mMappedJar.getPath(), "--access-transformer", "BuildData/mappings/" + versionInfo.getAccessTransforms(),
                    "-m", "BuildData/mappings/" + versionInfo.getPackageMappings(), "-o", finalMappedJar.getPath() );
        }

        runProcess( CWD, "sh", mvn, "install:install-file", "-Dfile=" + finalMappedJar, "-Dpackaging=jar", "-DgroupId=org.spigotmc",
                "-DartifactId=minecraft-server", "-Dversion=" + versionInfo.getMinecraftVersion() + "-SNAPSHOT" );

        File decompileDir = new File( workDir, "decompile-" + mappingsVersion );
        if ( !decompileDir.exists() )
        {
            decompileDir.mkdir();

            File clazzDir = new File( decompileDir, "classes" );
            unzip( finalMappedJar, clazzDir, new Predicate<String>()
            {

                @Override
                public boolean apply(String input)
                {
                    return input.startsWith( "net/minecraft/server" );
                }
            } );
            if ( versionInfo.getDecompileCommand() == null )
            {
                versionInfo.setDecompileCommand( "java -jar BuildData/bin/fernflower.jar -dgs=1 -hdc=0 -rbr=0 -asc=1 -udv=0 {0} {1}" );
            }

            runProcess( CWD, MessageFormat.format( versionInfo.getDecompileCommand(), clazzDir.getPath(), decompileDir.getPath() ).split( " " ) );
        }

        System.out.println( "Applying CraftBukkit Patches" );
        File nmsDir = new File( craftBukkit, "src/main/java/net" );
        if ( nmsDir.exists() )
        {
            System.out.println( "Backing up NMS dir" );
            FileUtils.moveDirectory( nmsDir, new File( workDir, "nms.old." + System.currentTimeMillis() ) );
        }
        File patchDir = new File( craftBukkit, "nms-patches" );
        for ( File file : patchDir.listFiles() )
        {
            if ( !file.getName().endsWith( ".patch" ) )
            {
                continue;
            }

            String targetFile = "net/minecraft/server/" + file.getName().replaceAll( ".patch", ".java" );

            File clean = new File( decompileDir, targetFile );
            File t = new File( nmsDir.getParentFile(), targetFile );
            t.getParentFile().mkdirs();

            System.out.println( "Patching with " + file.getName() );

            List<String> readFile = Files.readLines( file, Charsets.UTF_8 );

            // Manually append prelude if it is not found in the first few lines.
            boolean preludeFound = false;
            for ( int i = 0; i < Math.min( 3, readFile.size() ); i++ )
            {
                if ( readFile.get( i ).startsWith( "+++" ) )
                {
                    preludeFound = true;
                    break;
                }
            }
            if ( !preludeFound )
            {
                readFile.add( 0, "+++" );
            }

            Patch parsedPatch = DiffUtils.parseUnifiedDiff( readFile );
            List<?> modifiedLines = DiffUtils.patch( Files.readLines( clean, Charsets.UTF_8 ), parsedPatch );

            BufferedWriter bw = new BufferedWriter( new FileWriter( t ) );
            for ( String line : (List<String>) modifiedLines )
            {
                bw.write( line );
                bw.newLine();
            }
            bw.close();
        }
        File tmpNms = new File( craftBukkit, "tmp-nms" );
        FileUtils.copyDirectory( nmsDir, tmpNms );

        craftBukkitGit.branchDelete().setBranchNames( "patched" ).setForce( true ).call();
        craftBukkitGit.checkout().setCreateBranch( true ).setForce( true ).setName( "patched" ).call();
        craftBukkitGit.add().addFilepattern( "src/main/java/net/" ).call();
        craftBukkitGit.commit().setMessage( "CraftBukkit $ " + new Date() ).call();
        craftBukkitGit.checkout().setName( buildInfo.getRefs().getCraftBukkit() ).call();

        FileUtils.moveDirectory( tmpNms, nmsDir );

        File spigotApi = new File( spigot, "Bukkit" );
        if ( !spigotApi.exists() )
        {
            clone( "file://" + bukkit.getAbsolutePath(), spigotApi );
        }
        File spigotServer = new File( spigot, "CraftBukkit" );
        if ( !spigotServer.exists() )
        {
            clone( "file://" + craftBukkit.getAbsolutePath(), spigotServer );
        }

        // Git spigotApiGit = Git.open( spigotApi );
        // Git spigotServerGit = Git.open( spigotServer );
        if ( !skipCompile )
        {
            System.out.println( "Compiling Bukkit" );
            runProcess( bukkit, "sh", mvn, "clean", "install" );
            if ( generateDocs )
            {
                runProcess( bukkit, "sh", mvn, "javadoc:jar" );
            }
            if ( generateSource )
            {
                runProcess( bukkit, "sh", mvn, "source:jar" );
            }

            System.out.println( "Compiling CraftBukkit" );
            runProcess( craftBukkit, "sh", mvn, "clean", "install" );
        }

        try
        {
            runProcess( spigot, applyPatchesShell, "applyPatches.sh" );
            System.out.println( "*** Spigot patches applied!" );

            if ( !skipCompile )
            {
                System.out.println( "Compiling Spigot & Spigot-API" );
                runProcess( spigot, "sh", mvn, "clean", "install" );
            }
        } catch ( Exception ex )
        {
            System.err.println( "Error compiling Spigot. Please check the wiki for FAQs." );
            System.err.println( "If this does not resolve your issue then please pastebin the entire BuildTools.log.txt file when seeking support." );
            ex.printStackTrace();
            System.exit( 1 );
        }

        for ( int i = 0; i < 35; i++ )
        {
            System.out.println( " " );
        }

        if ( !skipCompile )
        {
            System.out.println( "Success! Everything compiled successfully. Copying final .jar files now." );
            copyJar( "CraftBukkit/target", "craftbukkit", new File( outputDir.value( options ), "craftbukkit-" + versionInfo.getMinecraftVersion() + ".jar" ) );
            copyJar( "Spigot/Spigot-Server/target", "spigot", new File( outputDir.value( options ), "spigot-" + versionInfo.getMinecraftVersion() + ".jar" ) );
        }
    }

    private static boolean checkHash(File vanillaJar, VersionInfo versionInfo) throws IOException
    {
        String hash = Files.hash( vanillaJar, Hashing.md5() ).toString();
        if ( !dev && versionInfo.getMinecraftHash() != null && !hash.equals( versionInfo.getMinecraftHash() ) )
        {
            System.err.println( "**** Warning, Minecraft jar hash of " + hash + " does not match stored hash of " + versionInfo.getMinecraftHash() );
            return false;
        } else
        {
            System.out.println( "Found good Minecraft hash (" + hash + ")" );
            return true;
        }
    }

    public static final String get(String url) throws IOException
    {
        URLConnection con = new URL( url ).openConnection();
        con.setConnectTimeout( 5000 );
        con.setReadTimeout( 5000 );

        InputStreamReader r = null;
        try
        {
            r = new InputStreamReader( con.getInputStream() );

            return CharStreams.toString( r );
        } finally
        {
            if ( r != null )
            {
                r.close();
            }
        }
    }

    public static void copyJar(String path, final String jarPrefix, File outJar) throws Exception
    {
        File[] files = new File( path ).listFiles( new FilenameFilter()
        {
            @Override
            public boolean accept(File dir, String name)
            {
                return name.startsWith( jarPrefix ) && name.endsWith( ".jar" );
            }
        } );

        if ( !outJar.getParentFile().isDirectory() )
        {
            outJar.getParentFile().mkdirs();
        }

        for ( File file : files )
        {
            System.out.println( "Copying " + file.getName() + " to " + outJar.getAbsolutePath() );
            Files.copy( file, outJar );
            System.out.println( "  - Saved as " + outJar );
        }
    }

    public static void pull(Git repo, String ref) throws Exception
    {
        System.out.println( "Pulling updates for " + repo.getRepository().getDirectory() );

        repo.reset().setRef( "origin/master" ).setMode( ResetCommand.ResetType.HARD ).call();
        repo.fetch().call();

        System.out.println( "Successfully fetched updates!" );

        repo.reset().setRef( ref ).setMode( ResetCommand.ResetType.HARD ).call();
        if ( ref.equals( "master" ) )
        {
            repo.reset().setRef( "origin/master" ).setMode( ResetCommand.ResetType.HARD ).call();
        }
        System.out.println( "Checked out: " + ref );
    }

    public static int runProcess(File workDir, String... command) throws Exception
    {
        if ( msysDir != null )
        {
            if ( "bash".equals( command[0] ) )
            {
                command[0] = "git-bash";
            }
            String[] shim = new String[]
            {
                "cmd.exe", "/C"
            };
            command = ObjectArrays.concat( shim, command, String.class );
        }
        return runProcess0( workDir, command );
    }

    private static int runProcess0(File workDir, String... command) throws Exception
    {
        ProcessBuilder pb = new ProcessBuilder( command );
        pb.directory( workDir );
        pb.environment().put( "JAVA_HOME", System.getProperty( "java.home" ) );
        if ( !pb.environment().containsKey( "MAVEN_OPTS" ) )
        {
            pb.environment().put( "MAVEN_OPTS", "-Xmx1024M" );
        }
        if ( msysDir != null )
        {
            String pathEnv = null;
            for ( String key : pb.environment().keySet() )
            {
                if ( key.equalsIgnoreCase( "path" ) )
                {
                    pathEnv = key;
                }
            }
            if ( pathEnv == null )
            {
                throw new IllegalStateException( "Could not find path variable!" );
            }

            String path = pb.environment().get( pathEnv );
            path += ";" + msysDir.getAbsolutePath();
            path += ";" + new File( msysDir, "bin" ).getAbsolutePath();
            pb.environment().put( pathEnv, path );
        }

        final Process ps = pb.start();

        new Thread( new StreamRedirector( ps.getInputStream(), System.out ) ).start();
        new Thread( new StreamRedirector( ps.getErrorStream(), System.err ) ).start();

        int status = ps.waitFor();

        if ( status != 0 )
        {
            throw new RuntimeException( "Error running command, return status !=0: " + Arrays.toString( command ) );
        }

        return status;
    }

    @RequiredArgsConstructor
    private static class StreamRedirector implements Runnable
    {

        private final InputStream in;
        private final PrintStream out;

        @Override
        public void run()
        {
            BufferedReader br = new BufferedReader( new InputStreamReader( in ) );
            try
            {
                String line;
                while ( ( line = br.readLine() ) != null )
                {
                    out.println( line );
                }
            } catch ( IOException ex )
            {
                throw Throwables.propagate( ex );
            }
        }
    }

    public static void unzip(File zipFile, File targetFolder) throws IOException
    {
        unzip( zipFile, targetFolder, null );
    }

    public static void unzip(File zipFile, File targetFolder, Predicate<String> filter) throws IOException
    {
        targetFolder.mkdir();
        ZipFile zip = new ZipFile( zipFile );

        try
        {
            for ( Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); )
            {
                ZipEntry entry = entries.nextElement();

                if ( filter != null )
                {
                    if ( !filter.apply( entry.getName() ) )
                    {
                        continue;
                    }
                }

                File outFile = new File( targetFolder, entry.getName() );

                if ( entry.isDirectory() )
                {
                    outFile.mkdirs();
                    continue;
                }
                if ( outFile.getParentFile() != null )
                {
                    outFile.getParentFile().mkdirs();
                }

                InputStream is = zip.getInputStream( entry );
                OutputStream os = new FileOutputStream( outFile );
                try
                {
                    ByteStreams.copy( is, os );
                } finally
                {
                    is.close();
                    os.close();
                }

                System.out.println( "Extracted: " + outFile );
            }
        } finally
        {
            zip.close();
        }
    }

    public static void clone(String url, File target) throws GitAPIException, IOException
    {
        System.out.println( "Starting clone of " + url + " to " + target );

        Git result = Git.cloneRepository().setURI( url ).setDirectory( target ).call();

        try
        {
            StoredConfig config = result.getRepository().getConfig();
            config.setBoolean( "core", null, "autocrlf", autocrlf );
            config.save();

            System.out.println( "Cloned git repository " + url + " to " + target.getAbsolutePath() + ". Current HEAD: " + commitHash( result ) );
        } finally
        {
            result.close();
        }
    }

    public static String commitHash(Git repo) throws GitAPIException
    {
        return Iterables.getOnlyElement( repo.log().setMaxCount( 1 ).call() ).getName();
    }

    public static File download(String url, File target) throws IOException
    {
        System.out.println( "Starting download of " + url );

        byte[] bytes = Resources.toByteArray( new URL( url ) );

        System.out.println( "Downloaded file: " + target + " with md5: " + Hashing.md5().hashBytes( bytes ).toString() );

        Files.write( bytes, target );

        return target;
    }

    public static void disableHttpsCertificateCheck()
    {
        // This globally disables certificate checking
        // http://stackoverflow.com/questions/19723415/java-overriding-function-to-disable-ssl-certificate-check
        try
        {
            TrustManager[] trustAllCerts = new TrustManager[]
            {
                new X509TrustManager()
                {
                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers()
                    {
                        return null;
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] certs, String authType)
                    {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] certs, String authType)
                    {
                    }
                }
            };

            // Trust SSL certs
            SSLContext sc = SSLContext.getInstance( "SSL" );
            sc.init( null, trustAllCerts, new SecureRandom() );
            HttpsURLConnection.setDefaultSSLSocketFactory( sc.getSocketFactory() );

            // Trust host names
            HostnameVerifier allHostsValid = new HostnameVerifier()
            {
                @Override
                public boolean verify(String hostname, SSLSession session)
                {
                    return true;
                }
            };
            HttpsURLConnection.setDefaultHostnameVerifier( allHostsValid );
        } catch ( NoSuchAlgorithmException ex )
        {
            System.out.println( "Failed to disable https certificate check" );
            ex.printStackTrace( System.err );
        } catch ( KeyManagementException ex )
        {
            System.out.println( "Failed to disable https certificate check" );
            ex.printStackTrace( System.err );
        }
    }

    public static void logOutput()
    {
        try
        {
            final OutputStream logOut = new BufferedOutputStream( new FileOutputStream( LOG_FILE ) );

            Runtime.getRuntime().addShutdownHook( new Thread()
            {
                @Override
                public void run()
                {
                    System.setOut( new PrintStream( new FileOutputStream( FileDescriptor.out ) ) );
                    System.setErr( new PrintStream( new FileOutputStream( FileDescriptor.err ) ) );
                    try
                    {
                        logOut.close();
                    } catch ( IOException ex )
                    {
                        // We're shutting the jvm down anyway.
                    }
                }
            } );

            System.setOut( new PrintStream( new TeeOutputStream( System.out, logOut ) ) );
            System.setErr( new PrintStream( new TeeOutputStream( System.err, logOut ) ) );
        } catch ( FileNotFoundException ex )
        {
            System.err.println( "Failed to create log file: " + LOG_FILE );
        }
    }
}

package consulo.internal.gradle.artifact.deployer;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * @author VISTALL
 * @since 31/03/2023
 */
public class Deployer
{
	public void run(String args[]) throws Exception
	{
		String mavenHome = System.getenv("MAVEN_HOME");
		if(mavenHome == null)
		{
			mavenHome = args[0];
		}

		Path target = Paths.get("build");

		Path extractDirectory = target.resolve("extract");

		cleanAndDownloadGradle(target, extractDirectory);

		Path gradleDirectory = Files.walk(extractDirectory).filter(path -> !path.equals(extractDirectory)).findAny().get();

		String distrName = gradleDirectory.getFileName().toString();

		String version = distrName.replace("gradle-", "");

		Path gradleLib = gradleDirectory.resolve("lib");

		List<MavenArtifact> jarLibsForDeploy = new ArrayList<>();
		Files.walk(gradleLib).forEach(path ->
		{
			String fileName = path.getFileName().toString();
			if(fileName.startsWith("gradle-") && fileName.endsWith(".jar"))
			{
				jarLibsForDeploy.add(new MavenArtifact(path));
			}
		});

		Path gradleSrc = gradleDirectory.resolve("src");
		if(Files.exists(gradleSrc))
		{
			System.out.println("Source directory exists. Making source artifacts");

			for(MavenArtifact mavenArtifact : jarLibsForDeploy)
			{
				String artifactId = mavenArtifact.jar.getFileName().toString();
				artifactId = artifactId.replace("gradle-", "").replace(".jar", "").replace("-" + version, "");

				Path srcJarDistribution = gradleSrc.resolve(artifactId);
				if(Files.exists(srcJarDistribution))
				{
					Path sourceJar = gradleSrc.resolve("gradle-" + artifactId + "-" + version + "-sources.jar");

					mavenArtifact.sourcesJar = sourceJar;

					Files.deleteIfExists(sourceJar); // just while tests

					zipDirectory(sourceJar, srcJarDistribution);
				}
			}
		}

		postProcess(target, jarLibsForDeploy, version);

		for(MavenArtifact mavenArtifact : jarLibsForDeploy)
		{
			if(mavenArtifact.sourcesJar == null)
			{
				System.out.println("Missing source artifact for: " + mavenArtifact.jar);
			}
		}

		boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.US).contains("windows");

		System.out.println("Deploying artifacts");
		for(MavenArtifact mavenArtifact : jarLibsForDeploy)
		{
			String artifactId = mavenArtifact.jar.getFileName().toString().replace("-" + version + ".jar", "");

			System.out.println("Deploying " + artifactId);

			String arg = runAtMaven(artifactId, version, "jar", mavenArtifact.jar.toAbsolutePath(), mavenArtifact.sourcesJar == null ? null : mavenArtifact.sourcesJar.toAbsolutePath());

			String commandFile = mavenHome + File.separator + "bin" + File.separator + "mvn" + (isWindows ? ".cmd" : "");

			ProcessBuilder processBuilder = new ProcessBuilder();
			processBuilder.inheritIO();
			String[] splittedArgs = arg.split(" ");
			List<String> to = new ArrayList<>();
			to.add(commandFile);
			Collections.addAll(to, splittedArgs);
			processBuilder.command(to);

			int i = processBuilder.start().waitFor();
			if(i != 0)
			{
				break;
			}
		}
	}

	protected void postProcess(Path target, List<MavenArtifact> jarLibsForDeploy, String version) throws Exception
	{
	}

	protected static String runAtMaven(String artifactId, String version, String packaging, Path filePath, Path sourceFilePath)
	{
		StringBuilder builder = new StringBuilder();
		builder.append("deploy:deploy-file");
		builder.append(" -DgroupId=").append("consulo.internal.gradle.plugin");
		builder.append(" -DartifactId=").append(artifactId);
		builder.append(" -Dversion=").append(version);
		builder.append(" -Dpackaging=").append(packaging);
		builder.append(" -DrepositoryId=").append("consulo");
		builder.append(" -Durl=").append("https://maven.consulo.io/repository/snapshots/");
		builder.append(" -Dfile=").append(filePath);
		if(sourceFilePath != null)
		{
			builder.append(" -Dsources=").append(sourceFilePath);
		}

		return builder.toString();
	}

	protected static void cleanAndDownloadGradle(Path target, Path extractDirectory) throws Exception
	{
		System.out.println("Preparing build directory");

		if(Files.exists(target))
		{
			MoreFiles.deleteRecursively(target, RecursiveDeleteOption.ALLOW_INSECURE);
		}

		Files.createDirectory(target);

		Path zipDistribution = target.resolve("gradle-distribution.zip");

		try (InputStream stream = new URL(Gradle.URL).openStream())
		{
			System.out.println("Downloading gradle distribution. Target: " + target.toFile().getCanonicalPath());

			Files.copy(stream, zipDistribution);
		}

		Files.createDirectory(extractDirectory);

		System.out.println("Extracting distribution");

		unzip(zipDistribution, extractDirectory, (name) -> name.contains("examples"));
	}

	protected static void zipDirectory(Path jarPath, Path jarSource) throws IOException
	{
		try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(jarPath), StandardCharsets.UTF_8))
		{
			Files.walk(jarSource).forEach(maybeJavaFile ->
			{
				if(Files.isDirectory(maybeJavaFile))
				{
					return;
				}

				Path temp = jarSource.relativize(maybeJavaFile);

				String entryName = temp.toString().replace("\\", "/");

				try (InputStream fileStream = Files.newInputStream(maybeJavaFile))
				{
					addToZipFile(entryName, fileStream, zipOutputStream);
				}
				catch(IOException e)
				{
					throw new RuntimeException(e);
				}
			});
		}
	}

	public static void addToZipFile(String entryName, InputStream stream, ZipOutputStream zos) throws IOException
	{
		ZipEntry zipEntry = new ZipEntry(entryName);
		zos.putNextEntry(zipEntry);

		byte[] bytes = new byte[1024];
		int length;
		while((length = stream.read(bytes)) >= 0)
		{
			zos.write(bytes, 0, length);
		}

		zos.closeEntry();
	}

	public static void unzip(Path zipFilePath, Path extractTo, Predicate<String> fileAccepter) throws IOException
	{
		try (ZipInputStream zipIn = new ZipInputStream(Files.newInputStream(zipFilePath)))
		{
			ZipEntry entry = zipIn.getNextEntry();
			while(entry != null)
			{
				String name = entry.getName();
				if(fileAccepter.test(name))
				{
					zipIn.closeEntry();
					entry = zipIn.getNextEntry();
					continue;
				}

				Path filePath = extractTo.resolve(name);
				if(!entry.isDirectory())
				{
					extractFile(zipIn, filePath);
				}
				else if(!Files.exists(filePath))
				{
					Files.createDirectory(filePath);
				}
				zipIn.closeEntry();
				entry = zipIn.getNextEntry();
			}
		}
	}

	private static void extractFile(ZipInputStream zipIn, Path filePath) throws IOException
	{
		Files.createDirectories(filePath.getParent());

		try (BufferedOutputStream bos = new BufferedOutputStream(Files.newOutputStream(filePath)))
		{
			byte[] bytesIn = new byte[1024];
			int read;
			while((read = zipIn.read(bytesIn)) != -1)
			{
				bos.write(bytesIn, 0, read);
			}
		}
	}
}

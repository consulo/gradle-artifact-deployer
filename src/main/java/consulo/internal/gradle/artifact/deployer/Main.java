package consulo.internal.gradle.artifact.deployer;

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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;

public class Main
{
	static class MavenArtifact
	{
		Path jar;
		Path sourcesJar;

		MavenArtifact(Path jar)
		{
			this.jar = jar;
		}
	}

	public static void main(String[] args) throws Exception
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

					try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(sourceJar), StandardCharsets.UTF_8))
					{
						Files.walk(srcJarDistribution).forEach(maybeJavaFile ->
						{
							if(Files.isDirectory(maybeJavaFile))
							{
								return;
							}

							Path temp = srcJarDistribution.relativize(maybeJavaFile);

							String entryName = temp.toString().replace("\\", "/");

							try (InputStream fileStream = Files.newInputStream(maybeJavaFile))
							{
								addToZipFile(entryName, fileStream, zipOutputStream);
							}
							catch(IOException e)
							{
								e.printStackTrace();
							}
						});
					}
				}
			}
		}

		for(MavenArtifact mavenArtifact : jarLibsForDeploy)
		{
			if(mavenArtifact.sourcesJar == null)
			{
				System.out.println("Missing source artifact for: " + mavenArtifact.jar);
			}
		}

		boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.US).contains("windows");

		System.out.println("Deploying arifacts");
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

	private static String runAtMaven(String artifactId, String version, String packaging, Path filePath, Path sourceFilePath)
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

	private static void cleanAndDownloadGradle(Path target, Path extractDirectory) throws Exception
	{
		String url = "https://downloads.gradle.org/distributions/gradle-4.10-all.zip";
		//String url = "https://downloads.gradle.org/distributions/gradle-4.10-bin.zip";

		System.out.println("Preparing build directory");

		if(Files.exists(target))
		{
			MoreFiles.deleteRecursively(target, RecursiveDeleteOption.ALLOW_INSECURE);
		}

		Files.createDirectory(target);

		Path zipDistribution = target.resolve("gradle-distribuition.zip");

		try (InputStream stream = new URL(url).openStream())
		{
			System.out.println("Downloading gradle distribution. Target: " + target.toFile().getCanonicalPath());

			Files.copy(stream, zipDistribution);
		}

		Files.createDirectory(extractDirectory);

		System.out.println("Extracting distribution");

		unzip(zipDistribution, extractDirectory);
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

	public static void unzip(Path zipFilePath, Path extractTo) throws IOException
	{
		try (ZipInputStream zipIn = new ZipInputStream(Files.newInputStream(zipFilePath)))
		{
			ZipEntry entry = zipIn.getNextEntry();
			while(entry != null)
			{
				String name = entry.getName();
				if(name.contains("examples"))
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
				else
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

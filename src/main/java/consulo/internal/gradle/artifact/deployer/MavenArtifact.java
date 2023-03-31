package consulo.internal.gradle.artifact.deployer;

import java.nio.file.Path;

/**
* @author VISTALL
* @since 31/03/2023
*/
class MavenArtifact
{
	Path jar;
	Path sourcesJar;

	MavenArtifact(Path jar)
	{
		this.jar = jar;
	}
}

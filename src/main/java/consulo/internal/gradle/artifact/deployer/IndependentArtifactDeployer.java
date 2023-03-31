package consulo.internal.gradle.artifact.deployer;

/**
 * Deploy each gradle-*.jar libs to own artifacts
 *
 * This is not work at jdk 9+ module system, due they duplicate packages
 */
public class IndependentArtifactDeployer extends Deployer
{
	public static void main(String[] args) throws Exception
	{
		new IndependentArtifactDeployer().run(args);
	}
}

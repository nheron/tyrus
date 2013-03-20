/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.tyrus.tests.qa.tools;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.websocket.ClientEndpoint;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.server.ServerApplicationConfig;
import javax.websocket.server.ServerEndpoint;
import org.glassfish.embeddable.BootstrapProperties;
import org.glassfish.embeddable.GlassFishException;
import org.glassfish.embeddable.GlassFishProperties;
import org.glassfish.embeddable.GlassFishRuntime;
import org.glassfish.embeddable.archive.ScatteredArchive;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.glassfish.embeddable.Deployer;
import org.glassfish.tyrus.tests.qa.config.AppConfig;
import org.glassfish.tyrus.tests.qa.lifecycle.LifeCycleDeployment;

/**
 *
 * @author Michal Conos (michal.conos at oracle.com)
 */
public class GlassFishToolkit implements ServerToolkit {

    private String installRoot;
    private ScatteredArchive deploy;
    private org.glassfish.embeddable.GlassFish glassFish;
    private final static Logger logger = Logger.getLogger(GlassFishToolkit.class.getName());
    public final static String PLATFORM_KEY = "GlassFish_Platform";
    public final static String INSTANCE_ROOT_PROP_NAME = "com.sun.aas.instanceRoot";
    public static final String INSTALL_ROOT_PROP_NAME = "com.sun.aas.installRoot";
    public static final String CONFIG_FILE_URI_PROP_NAME = "org.glassfish.embeddable.configFileURI";
    private static final String NETWORK_LISTENER_KEY = "embedded-glassfish-config."
            + "server.network-config.network-listeners.network-listener.%s";
    public static String thisArtifactId = "org.glassfish.embedded:maven-embedded-glassfish-plugin";
    private static String SHELL_JAR = "lib/embedded/glassfish-embedded-static-shell.jar";
    private static String FELIX_JAR = "osgi/felix/bin/felix.jar";
    private static final String EMBEDDED_GROUP_ID = "org.glassfish.main.extras";
    private static final String EMBEDDED_ALL = "glassfish-embedded-all";
    private static final String EMBEDDED_ARTIFACT_PREFIX = "glassfish-embedded-";
    private static final String GF_API_GROUP_ID = "org.glassfish";
    private static final String GF_API_ARTIFACT_ID = "simple-glassfish-api";
    private static final String DEFAULT_GF_VERSION = "3.1";
    private static String gfVersion;
    private static final String glassfishWeb =
            "<!DOCTYPE glassfish-web-app PUBLIC \"-//GlassFish.org//DTD GlassFish Application Server 3.1 Servlet 3.0//EN\" \"http://glassfish.org/dtds/glassfish-web-app_3_0-1.dtd\">"
            + "<glassfish-web-app error-url=\"\">"
            + "<context-root>%s</context-root>"
            + "<class-loader delegate=\"true\"/>"
            + "<jsp-config>"
            + "<property name=\"keepgenerated\" value=\"true\">"
            + "<description>Keep a copy of the generated servlet class' java code.</description>"
            + "</property>"
            + "</jsp-config>"
            + "</glassfish-web-app>";
    private String appName;
    private AppConfig config;
    
    public GlassFishToolkit(AppConfig config) {
        this.config = config;
        this.installRoot = config.getInstallRoot();
    }

    private ClassLoader getInstalledGFClassLoader(String installRoot) throws Exception {
        File gfJar = new File(installRoot, SHELL_JAR);
        File felixJar = new File(installRoot, FELIX_JAR);
        URLClassLoader classLoader = new URLClassLoader(
                new URL[]{gfJar.toURI().toURL(), felixJar.toURI().toURL()}, getClass().getClassLoader());
        return classLoader;
    }

    private File createWebXml(String path) throws IOException {
        File webXml = File.createTempFile("glassfish-web", "xml");
        FileUtils.writeStringToFile(webXml, String.format(glassfishWeb, path));
        return webXml;
    }

    private String getClazzCanonicalName(File clazz) {
        logger.log(Level.INFO, "getClazzCanonicalName:{0}", clazz.toString());
        return FilenameUtils.removeExtension(clazz.toString()).replaceFirst("target/classes/", "").replace('/', '.');
    }

    private File getDirname(File clazz) {
        return new File(new File(getClazzCanonicalName(clazz).replace('.', '/')).getParent());
    }

    private Class<?> getClazzForFile(File clazz) throws ClassNotFoundException {
        String clazzCanonicalName = getClazzCanonicalName(clazz);
        logger.log(Level.INFO, "getClazzForFile(): {0}", clazzCanonicalName);
        return Class.forName(clazzCanonicalName);
    }

    private boolean isBlackListed(File clazz) throws ClassNotFoundException {
        //logger.log(Level.INFO, "File? {0}", clazzCanonicalName);

        Class tryMe = getClazzForFile(clazz);



        logger.log(Level.INFO, "File? {0}", tryMe.getCanonicalName());

        logger.log(Level.INFO, "Interfaces:{0}", tryMe.getInterfaces());
        if (Arrays.asList(tryMe.getInterfaces()).contains((ServerApplicationConfig.class))) {
            logger.log(Level.INFO, "ServerApplicationConfig : {0}", tryMe.getCanonicalName());
            return true;
        }
        if (tryMe.isAnnotationPresent(ServerEndpoint.class)) {
            logger.log(Level.INFO, "Annotated ServerEndpoint: {0}", tryMe.getCanonicalName());
            return true;
        }

        if (tryMe.isAnnotationPresent(ClientEndpoint.class)) {
            logger.log(Level.INFO, "Annotated ClientEndpoint: {0}", tryMe.getCanonicalName());
            return true;
        }
        //Endpoint itself is not blacklisted
        //if (Endpoint.class.isAssignableFrom(tryMe)) {
        //    logger.log(Level.INFO, "Programmatic Endpoint: {0}", tryMe.getCanonicalName());
        //    return true;
        //}

        return false;
    }

    private File getFileForClazz(Class clazz) {
        logger.log(Level.INFO, "Obtaining file for : {0}", clazz.getCanonicalName());

        String clazzBasename = new File(clazz.getCanonicalName().replace('.', '/')).getName();

        return new File(getDirname(new File(clazz.toString())), clazzBasename);
    }

    public ScatteredArchive makeWar(Class clazz, String path) throws IOException, ClassNotFoundException {
        ScatteredArchive archive = new ScatteredArchive("testapp", ScatteredArchive.Type.WAR);
        String name = clazz.getName();

        //archive.addClassPath(new File("target/classes"));
        List<File> addClasses = new ArrayList<>();
        File tempDir = FileUtils.getTempDirectory();
        File dstDirectory = new File(tempDir, "lib");
        FileUtils.forceMkdir(dstDirectory);
        File source = new File("target/classes");
        FileUtils.copyDirectory(source, dstDirectory);
        logger.log(Level.INFO, "tempdir:{0}", dstDirectory.toString());
        String targetCanonicalName = clazz.getCanonicalName();
        for (File addMe : FileUtils.listFiles(dstDirectory, new String[]{"class"}, true)) {
            File srcClazz = new File(addMe.toString().replaceFirst(dstDirectory.toString(), "target/classes"));
            String srcClazzCanonicalName = getClazzForFile(srcClazz).getCanonicalName();
            if (srcClazzCanonicalName != null && srcClazzCanonicalName.equals(targetCanonicalName)) {
                continue;
            }
            if (isBlackListed(srcClazz)) {
                logger.log(Level.INFO, "Deleting : {0}", addMe.toString());
                FileUtils.forceDelete(addMe);
            }


        }
        archive.addClassPath(dstDirectory);
        archive.addMetadata(createWebXml(path), "glassfish-web.xml");
        return archive;
    }

    @Override
    public void startServer() throws DeploymentException {
        ClassLoader cl;
        try {
            cl = getInstalledGFClassLoader(installRoot);
            //String gfModule="/home/mikc/glassfish3/glassfish/modules/glassfish.jar";
            //Iterator<RuntimeBuilder> runtimeBuilders = ServiceLoader.load(RuntimeBuilder.class, cl).iterator();

            //while (runtimeBuilders.hasNext()) {
            //      RuntimeBuilder builder = runtimeBuilders.next();
            //}
            GlassFishProperties glassfishProperties = new GlassFishProperties();
            //glassfishProperties.setPort("http-listener", 8080);
            BootstrapProperties bp = new BootstrapProperties();
            //String httpListener = String.format(NETWORK_LISTENER_KEY, "http-listener");
            //glassfishProperties.setProperty(httpListener + ".port", String.valueOf(9090));
            //glassfishProperties.setProperty(httpListener + ".enabled", "true");
            // bp.setProperty(PLATFORM_KEY, "Static");
            bp.setInstallRoot(installRoot);
            glassfishProperties.setInstanceRoot(installRoot + "/domains/domain1");
            GlassFishRuntime gfr = GlassFishRuntime.bootstrap(bp, cl);
            glassFish = gfr.newGlassFish(glassfishProperties);

            // Start Embedded GlassFish
            glassFish.start();
            
            Deployer deployer = glassFish.getDeployer();
            appName = deployer.deploy(deploy.toURI());
        } catch (ClassNotFoundException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex.getMessage());
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex.getMessage());
        }
    }

    @Override
    public void stopServer() {
        if (glassFish != null) {
            try {
                if(appName!=null) {
                    Deployer deployer = glassFish.getDeployer();
                    deployer.undeploy(appName);
                }
                glassFish.stop();
                glassFish.dispose();
            } catch (GlassFishException ex) {
                ex.printStackTrace();
                throw new RuntimeException(ex.getMessage());
            }
        }
    }
    
    @Override
    public void registerEndpoint(Class<?> endpoint) {
        try {
            this.deploy = makeWar(endpoint, config.getContextPath());
        } catch (IOException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex.getMessage());
        } catch (ClassNotFoundException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex.getMessage());
        }
    }
}
package org.infinispan.it.osgi;

import org.infinispan.Cache;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.test.TestingUtil;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;

import java.io.IOException;
import java.net.URL;

import static org.junit.Assert.assertEquals;

/**
 * @author mgencur
 */
@RunWith(PaxExam.class)
@Category(Osgi.class)
@ExamReactorStrategy(PerClass.class)
public class BasicInfinispanOSGiTest extends BaseInfinispanCoreOSGiTest {

   @Test
   public void testCustomIspnConfigFile() throws IOException {
      URL configURL = BasicInfinispanOSGiTest.class.getClassLoader().getResource("infinispan.xml");
      EmbeddedCacheManager cacheManager = new DefaultCacheManager(configURL.openStream());
      try {
         Cache<String, String> cache = cacheManager.getCache();
         cache.put("k1", "v1");
         assertEquals("v1", cache.get("k1"));
      } finally {
         TestingUtil.killCacheManagers(cacheManager);
      }
   }

   @Override
   protected void createCacheManagers() throws Throwable {
      //not used
   }

}
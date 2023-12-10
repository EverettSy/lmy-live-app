package org.lmy.live.framework.datasource.starter.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import org.apache.commons.lang3.StringUtils;
import org.apache.shardingsphere.driver.jdbc.core.driver.ShardingSphereDriverURLProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class NacosDriverURLProvider implements ShardingSphereDriverURLProvider {

    private static Logger logger = LoggerFactory.getLogger(NacosDriverURLProvider.class);
    private static final String NACOS_TYPE = "nacos:";
    private static final String GROUP = "DEFAULT_GROUP";


    @Override
    public boolean accept(String url) {
        return StringUtils.isNotBlank(url) && url.contains(NACOS_TYPE);
    }

    /**
     * 从 url 中获取到 nacos 的连接配置信息
     *
     * @param url （jdbc:shardingsphere:nacos:lmy.nacos.com:8848:lmy-live-user:shardingjdbc.yaml?username=lmy&&password=lmy&&namespace=lmy-live-test）
     * @return
     */
    @Override
    public byte[] getContent(final String url) {
        if (StringUtils.isEmpty(url)) {
            return null;
        }
        //得到例如：lmy.nacos.com:8848:lmy-live-user-shardingjdbc.yaml?username=lmy&&password=lmy&&namespace=lmy-live-test 格式的 url
        String nacosUrl = url.substring(url.lastIndexOf(NACOS_TYPE) + NACOS_TYPE.length());
        /**
         * 得到三个字符串，分别是：
         * lmy.nacos.com
         * 8848
         * lmy-live-user-shardingjdbc.yaml
         */
        String nacosStr[] = nacosUrl.split(":");
        String nacosFileStr = nacosStr[2];
        /**
         * 得到两个字符串
         * lmy-live-user-shardingjdbc.yaml
         * username=lmy&&password=lmy&&namespace=lmy-live-test
         */
        String nacosFileProp[] = nacosFileStr.split("\\?");
        String dataId = nacosFileProp[0];
        String acceptProp[] = nacosFileProp[1].split("&&");
        //这里获取到
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR,
                nacosStr[0] + ":" + nacosStr[1]);
        for (String propertyName : acceptProp) {
            String[] propertyItem = propertyName.split("=");
            String key = propertyItem[0];
            String value = propertyItem[1];
            if ("username".equals(key)) {
                properties.setProperty(PropertyKeyConst.USERNAME,
                        value);
            } else if ("password".equals(key)) {
                properties.setProperty(PropertyKeyConst.PASSWORD,
                        value);
            } else if ("namespace".equals(key)) {
                properties.setProperty(PropertyKeyConst.NAMESPACE,
                        value);
            }
        }
        ConfigService configService = null;
        try {
            configService =
                    NacosFactory.createConfigService(properties);
            String content = configService.getConfig(dataId,
                    GROUP, 6000);
            logger.info(content);
            return content.getBytes();
        } catch (NacosException e) {
            throw new RuntimeException(e);
        }
    }
}
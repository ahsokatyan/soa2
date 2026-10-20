#!/bin/sh
. "$(dirname "$0")/common.sh"
exec "$JAVA" -Xms32m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2 \
    -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true \
    "-Dcatalina.home=$TOMCAT_DIR" "-Dcatalina.base=$TOMCAT_BASE" \
    "-Djava.io.tmpdir=$TOMCAT_BASE/temp" \
    "-Djava.util.logging.config.file=$TOMCAT_BASE/conf/logging.properties" \
    -Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager \
    -cp "$TOMCAT_DIR/bin/bootstrap.jar:$TOMCAT_DIR/bin/tomcat-juli.jar" \
    org.apache.catalina.startup.Bootstrap start

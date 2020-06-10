#!/bin/bash -x

# The following parameters must be specified:
#   JBSDK_VERSION    - specifies the current version of OpenJDK e.g. 11_0_6
#   JDK_BUILD_NUMBER - specifies the number of OpenJDK build or the value of --with-version-build argument to configure
#   build_number     - specifies the number of JetBrainsRuntime build
#   bundle_type      - specifies bundle to bu built; possible values:
#                        jcef - the bundles with jcef
#                        empty - the bundles without jcef
#
# jbrsdk-${JBSDK_VERSION}-osx-x64-b${build_number}.tar.gz
# jbr-${JBSDK_VERSION}-osx-x64-b${build_number}.tar.gz
#
# $ ./java --version
# openjdk 11.0.6 2020-01-14
# OpenJDK Runtime Environment (build 11.0.6+${JDK_BUILD_NUMBER}-b${build_number})
# OpenJDK 64-Bit Server VM (build 11.0.6+${JDK_BUILD_NUMBER}-b${build_number}, mixed mode)
#

JBSDK_VERSION=$1
JDK_BUILD_NUMBER=$2
build_number=$3
bundle_type=$4
JBSDK_VERSION_WITH_DOTS=$(echo $JBSDK_VERSION | sed 's/_/\./g')
MAJOR_JBSDK_VERSION=$(echo $JBSDK_VERSION_WITH_DOTS | awk -F "." '{print $1}')

source jb/project/tools/common.sh

function create_jbr {

  if [ -z "${bundle_type}" ]; then
    JBR_BUNDLE=jbr
  else
    JBR_BUNDLE=jbr_${bundle_type}
  fi
  JBR_BASE_NAME=${JBR_BUNDLE}-${JBSDK_VERSION}
  cat modules.list > modules_tmp.list
  rm -rf ${BASE_DIR}/jbr
  rm -rf ${BASE_DIR}/${JBR_BUNDLE}

  JRE_CONTENTS=${BASE_DIR}/${JBR_BUNDLE}/Contents
  JRE_HOME=${JRE_CONTENTS}/Home
  if [ -d "${JRE_CONTENTS}" ]; then
    rm -rf ${JRE_CONTENTS}
  fi
  mkdir -p ${JRE_CONTENTS}

  JBR=${JBR_BASE_NAME}-osx-x64-b${build_number}

  echo Running jlink....
  ${BASE_DIR}/$JBRSDK_BUNDLE/Contents/Home/bin/jlink \
      --module-path ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/Home/jmods --no-man-pages --compress=2 \
      --add-modules $(xargs < modules_tmp.list | sed s/" "//g) --output ${JRE_HOME}  || exit $?
  grep -v "^JAVA_VERSION" ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/Home/release | grep -v "^MODULES" >> ${JRE_HOME}/release
  cp -R ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/MacOS ${JRE_CONTENTS}
  cp ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/Info.plist ${JRE_CONTENTS}

  rm -rf ${JRE_CONTENTS}/Frameworks || exit $?
  [ ! -z "${bundle_type}" ] && (cp -a jcef_mac/Frameworks ${JRE_CONTENTS} || exit $?)

  echo Creating ${JBR}.tar.gz ...
  [ ! -z "${bundle_type}" ] && cp -R ${BASE_DIR}/${JBR_BUNDLE} ${BASE_DIR}/jbr
  COPYFILE_DISABLE=1 tar -pczf ${JBR}.tar.gz --exclude='*.dSYM' --exclude='man' -C ${BASE_DIR} jbr || exit $?
  rm -rf ${BASE_DIR}/${JBR_BUNDLE}
}

JBRSDK_BASE_NAME=jbrsdk-${JBSDK_VERSION}

#git checkout -- modules.list
#git checkout -- src/java.desktop/share/classes/module-info.java
[ -z "$bundle_type" ] && (git apply -p0 < jb/project/tools/patches/exclude_jcef_module.patch || exit $?)

sh configure \
  --disable-warnings-as-errors \
  --with-debug-level=release \
  --with-vendor-name="${VENDOR_NAME}" \
  --with-vendor-version-string="${VENDOR_VERSION_STRING}" \
  --with-version-pre= \
  --with-version-build=${JDK_BUILD_NUMBER} \
  --with-version-opt=b${build_number} \
  --with-import-modules=./modular-sdk \
  --with-boot-jdk=`/usr/libexec/java_home -v 14` \
  --enable-cds=yes || exit $?

make images CONF=macosx-x86_64-server-release || exit $?

JSDK=build/macosx-x86_64-server-release/images/jdk-bundle
JBSDK=${JBRSDK_BASE_NAME}-osx-x64-b${build_number}

BASE_DIR=jre
JBRSDK_BUNDLE=jbrsdk

rm -rf $BASE_DIR
mkdir $BASE_DIR || exit $?
cp -a $JSDK/jdk-$MAJOR_JBSDK_VERSION.jdk $BASE_DIR/$JBRSDK_BUNDLE || exit $?

if [[ ! -z "$bundle_type" ]]; then
  cp -a jcef_mac/Frameworks $BASE_DIR/$JBRSDK_BUNDLE/Contents/
fi
if [ "$bundle_type" == "jcef" ]; then
  echo Creating $JBSDK.tar.gz ...
  sed 's/JBR/JBRSDK/g' ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/Home/release > release
  mv release ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/Home/release
  COPYFILE_DISABLE=1 tar -pczf $JBSDK.tar.gz -C $BASE_DIR \
    --exclude='._*' --exclude='.DS_Store' --exclude='*~' \
    --exclude='Home/demo' --exclude='Home/man' --exclude='Home/sample' \
    $JBRSDK_BUNDLE || exit $?
fi

create_jbr || exit $?

if [ "$bundle_type" == "jcef" ]; then
  make test-image || exit $?

  JBRSDK_TEST=$JBRSDK_BASE_NAME-osx-test-x64-b$build_number

  echo Creating $JBRSDK_TEST.tar.gz ...
  COPYFILE_DISABLE=1 tar -pczf $JBRSDK_TEST.tar.gz -C build/macosx-x86_64-server-release/images \
    --exclude='test/jdk/demos' test || exit $?
fi
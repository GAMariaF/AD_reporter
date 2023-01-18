rm -rf build
gradle wrapper
./gradlew fatJar
 cp src/main/groovy/analysis_codes.txt ./build/libs


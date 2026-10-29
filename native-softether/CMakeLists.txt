cmake_minimum_required(VERSION 3.22.1)
project(illumera_softether C)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Pinned upstream protocol/JNI sources; the only overridden source is the
# audited TLS implementation enforcing a user-provided certificate pin.
set(UPSTREAM_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../vendor/softether-android/src/main/cpp")
set(OPENSSL_ARCHIVE_DIR "${UPSTREAM_DIR}/../jniLibs/${ANDROID_ABI}")
set(OPENSSL_HEADER_DIR "${UPSTREAM_DIR}/openssl/include/openssl")
if(NOT EXISTS "${OPENSSL_ARCHIVE_DIR}/libssl.a" OR
   NOT EXISTS "${OPENSSL_ARCHIVE_DIR}/libcrypto.a")
    message(FATAL_ERROR "Pinned SoftEther OpenSSL static archives missing for ${ANDROID_ABI}")
endif()
# A clean OpenSSL source submodule has only *.h.in templates. The centralized
# Android workflows must run the pinned SoftEther header-only generator before
# CMake starts, without rebuilding/replacing ABI-specific libssl.a/libcrypto.a.
foreach(header ssl.h configuration.h opensslv.h)
    if(NOT EXISTS "${OPENSSL_HEADER_DIR}/${header}")
        message(FATAL_ERROR "Generated OpenSSL ${header} missing. Run vendor/softether-android/src/main/cpp/build-openssl-android.sh --headers-only before Gradle.")
    endif()
endforeach()

find_library(ANDROID_LOG_LIBRARY log REQUIRED)
find_library(ANDROID_LIBRARY android REQUIRED)
find_library(Z_LIBRARY z REQUIRED)
add_library(softether SHARED
    "${UPSTREAM_DIR}/jni/softether_jni.c"
    "${UPSTREAM_DIR}/softether-core/src/proto/softether_protocol.c"
    "${UPSTREAM_DIR}/softether-core/src/proto/softether_pack.c"
    "${UPSTREAM_DIR}/softether-core/src/proto/softether_nat_t.c"
    "${UPSTREAM_DIR}/softether-core/src/proto/rudp_transport.c"
    "${UPSTREAM_DIR}/softether-core/src/proto/softether_rudp.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/hardened_aes_wrapper.c"
    "${UPSTREAM_DIR}/softether-core/src/crypto/compress.c"
    "${UPSTREAM_DIR}/softether-core/src/socket/tcp_socket.c"
    "${UPSTREAM_DIR}/softether-core/src/packet/packet_handler.c"
    "${UPSTREAM_DIR}/softether-core/src/net/dhcp_client.c"
)
target_include_directories(softether PRIVATE
    "${UPSTREAM_DIR}/softether-core/include"
    "${UPSTREAM_DIR}/openssl/include"
)
target_link_libraries(softether PRIVATE
    ${ANDROID_LOG_LIBRARY}
    ${ANDROID_LIBRARY}
    ${Z_LIBRARY}
    "${OPENSSL_ARCHIVE_DIR}/libssl.a"
    "${OPENSSL_ARCHIVE_DIR}/libcrypto.a"
)
target_compile_options(softether PRIVATE -O2 -fPIC -Wall -Wno-unused-variable -Wno-unused-parameter)

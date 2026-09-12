cmake_minimum_required(VERSION 3.22.1)
project(tijack_converter LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Pin the Evo-capable amalgamated build so conversion stays reproducible.
# The downloaded C++/header are compiled into the APK; TI-JACK does not need
# network access when it is running on the phone.
set(TIVARS_COMMIT "ad61ebba76fc00f30bb9365aff86c35162a50189")
set(TIVARS_DIR "${CMAKE_CURRENT_BINARY_DIR}/tivars_lib_cpp")
file(MAKE_DIRECTORY "${TIVARS_DIR}")

set(TIVARS_CPP "${TIVARS_DIR}/tivars_lib_cpp.cpp")
set(TIVARS_HPP "${TIVARS_DIR}/tivars_lib_cpp.hpp")

if(NOT EXISTS "${TIVARS_CPP}")
    file(DOWNLOAD
        "https://raw.githubusercontent.com/adriweb/tivars_lib_cpp/${TIVARS_COMMIT}/dist/tivars_lib_cpp.cpp"
        "${TIVARS_CPP}"
        TLS_VERIFY ON
        STATUS TIVARS_CPP_STATUS
    )
    list(GET TIVARS_CPP_STATUS 0 TIVARS_CPP_CODE)
    if(NOT TIVARS_CPP_CODE EQUAL 0)
        message(FATAL_ERROR "Could not download pinned tivars_lib_cpp.cpp: ${TIVARS_CPP_STATUS}")
    endif()
endif()

if(NOT EXISTS "${TIVARS_HPP}")
    file(DOWNLOAD
        "https://raw.githubusercontent.com/adriweb/tivars_lib_cpp/${TIVARS_COMMIT}/dist/tivars_lib_cpp.hpp"
        "${TIVARS_HPP}"
        TLS_VERIFY ON
        STATUS TIVARS_HPP_STATUS
    )
    list(GET TIVARS_HPP_STATUS 0 TIVARS_HPP_CODE)
    if(NOT TIVARS_HPP_CODE EQUAL 0)
        message(FATAL_ERROR "Could not download pinned tivars_lib_cpp.hpp: ${TIVARS_HPP_STATUS}")
    endif()
endif()

add_library(
    tijack_converter
    SHARED
    tijack_converter.cpp
    "${TIVARS_CPP}"
)

target_include_directories(tijack_converter PRIVATE "${TIVARS_DIR}")
target_compile_definitions(tijack_converter PRIVATE TH_GDB_SUPPORT=0)
target_compile_options(tijack_converter PRIVATE -fexceptions -frtti)

find_library(log-lib log)
target_link_libraries(tijack_converter PRIVATE ${log-lib})

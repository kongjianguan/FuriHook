cmake_minimum_required(VERSION 3.22.1)
project(wmgpurge C)
add_library(wmgpurge SHARED purge.c)
target_compile_options(wmgpurge PRIVATE -Wall -Wextra -Werror)

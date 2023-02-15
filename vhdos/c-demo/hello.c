/* Classic "Wello World" without printf */

puts(s)
char *s;
{
	while (*s) putchar(*s++);
}

main(argc, argv)
int argc;
char **argv;
{
	puts("Hello, World!\n");
}
